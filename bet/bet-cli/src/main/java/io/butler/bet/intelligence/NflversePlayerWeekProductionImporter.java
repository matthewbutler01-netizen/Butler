package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerWeekProductionCoverageRepository;
import io.butler.bet.data.PlayerWeekProductionRepository;
import io.butler.bet.domain.PlayerWeekProduction;
import io.butler.bet.domain.PlayerWeekProductionCoverage;
import io.butler.bet.domain.RawScoringProduction;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Imports raw nflverse regular-season week production using exact GSIS-to-Sleeper identity mapping. */
public final class NflversePlayerWeekProductionImporter {
    public static final String SOURCE = NflversePlayerSeasonProductionImporter.SOURCE;
    private static final Set<String> EXTENDED_COLUMNS = Set.of(
        "passing_2pt_conversions",
        "carries",
        "rushing_2pt_conversions",
        "receiving_2pt_conversions",
        "fumble_recovery_tds",
        "special_teams_tds");

    private final PlayerRepository players;
    private final PlayerWeekProductionRepository production;
    private final PlayerWeekProductionCoverageRepository coverage;
    private final HttpClient http;

    public NflversePlayerWeekProductionImporter(Database database) {
        this(database, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build());
    }

    NflversePlayerWeekProductionImporter(Database database, HttpClient http) {
        Objects.requireNonNull(database, "database must not be null");
        this.players = new PlayerRepository(database);
        this.production = new PlayerWeekProductionRepository(database);
        this.coverage = new PlayerWeekProductionCoverageRepository(database);
        this.http = Objects.requireNonNull(http, "http must not be null");
    }

    public ImportResult refresh(int season) throws IOException, InterruptedException, SQLException {
        return fetchAndProcess(season, true);
    }

    public ImportResult preview(int season) throws IOException, InterruptedException, SQLException {
        return fetchAndProcess(season, false);
    }

    public ImportResult importCsv(int season, String statsCsv, String idsCsv, LocalDate asOfDate)
        throws SQLException {
        return processCsv(season, statsCsv, idsCsv, asOfDate, true);
    }

    public ImportResult previewCsv(int season, String statsCsv, String idsCsv, LocalDate asOfDate)
        throws SQLException {
        return processCsv(season, statsCsv, idsCsv, asOfDate, false);
    }

    private ImportResult fetchAndProcess(int season, boolean persist)
        throws IOException, InterruptedException, SQLException {
        requireSeason(season);
        return processCsv(
            season,
            download(statsUri(season), "nflverse weekly player stats for " + season),
            download(NflversePlayerSeasonProductionImporter.PLAYER_IDS_URI, "fantasy player id crosswalk"),
            LocalDate.now(),
            persist);
    }

    private ImportResult processCsv(
        int season,
        String statsCsv,
        String idsCsv,
        LocalDate asOfDate,
        boolean persist) throws SQLException {

        requireSeason(season);
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        List<Map<String, String>> rows = Csv.parse(requireText(statsCsv, "statsCsv"));
        List<Map<String, String>> idRows = Csv.parse(requireText(idsCsv, "idsCsv"));
        if (rows.isEmpty()) throw new IllegalArgumentException("nflverse weekly stats contain no data rows");
        if (idRows.isEmpty()) throw new IllegalArgumentException("player-id crosswalk contains no data rows");
        int rawSchema = detectRawScoringSchema(rows.getFirst());

        Map<String, String> sleeperByGsis = buildCrosswalk(idRows);
        Map<String, String> localBySleeper = buildLocalPlayerIndex();
        List<String> identityCovered = identityCoveredPlayers(sleeperByGsis, localBySleeper);
        Map<PlayerWeekKey, ProviderProduction> mapped = new LinkedHashMap<>();
        List<UnmatchedProviderRow> unmatched = new ArrayList<>();
        Map<Integer, Integer> providerByWeek = new LinkedHashMap<>();
        Map<Integer, Integer> unmatchedByWeek = new LinkedHashMap<>();
        int requestedSeasonRows = 0;
        int regularSeasonRows = 0;
        int excludedBlankPlayerRows = 0;

        for (Map<String, String> row : rows) {
            int rowSeason = parseNonNegativeInt(required(row, "season"), "season", "provider row");
            if (rowSeason != season) continue;
            requestedSeasonRows++;
            if (!"REG".equalsIgnoreCase(requireText(required(row, "season_type"), "season_type").trim())) {
                continue;
            }
            regularSeasonRows++;

            int week = parsePositiveInt(required(row, "week"), "week", "provider row");
            String gsisId = normalizeId(required(row, "player_id"));
            String parsingId = gsisId == null
                ? "blank player_id season " + season + " week " + week
                : gsisId;
            ProviderStats stats = parseProviderStats(row, rawSchema, parsingId);

            if (gsisId == null) {
                if (!stats.isZero()) {
                    throw new IllegalArgumentException(
                        "blank nflverse player_id carries nonzero stored production for season "
                            + season + " week " + week);
                }
                excludedBlankPlayerRows++;
                continue;
            }

            // Coverage provider-row counts contain only player-addressable rows. Zero-production
            // blank-id rows are explicitly excluded above and can never grant player identity coverage.
            providerByWeek.merge(week, 1, Integer::sum);

            String sleeperId = sleeperByGsis.get(gsisId);
            if (sleeperId == null) {
                unmatched.add(new UnmatchedProviderRow(gsisId, null, week, "No GSIS-to-Sleeper mapping"));
                unmatchedByWeek.merge(week, 1, Integer::sum);
                continue;
            }
            String localPlayerId = localBySleeper.get(sleeperId);
            if (localPlayerId == null) {
                unmatched.add(new UnmatchedProviderRow(gsisId, sleeperId, week, "No local player for Sleeper id"));
                unmatchedByWeek.merge(week, 1, Integer::sum);
                continue;
            }

            ProviderProduction provider = new ProviderProduction(
                localPlayerId, gsisId, sleeperId, week, stats, rawSchema);
            PlayerWeekKey key = new PlayerWeekKey(localPlayerId, week);
            ProviderProduction existing = mapped.putIfAbsent(key, provider);
            if (existing != null && !existing.equals(provider)) {
                throw new IllegalArgumentException(
                    "ambiguous nflverse weekly production for player " + localPlayerId + " week " + week);
            }
        }

        if (requestedSeasonRows == 0) {
            throw new IllegalArgumentException("nflverse weekly stats contain no rows for season: " + season);
        }
        if (regularSeasonRows == 0) {
            throw new IllegalArgumentException("nflverse weekly stats contain no REG rows for season: " + season);
        }

        Map<Integer, Integer> matchedByWeek = new LinkedHashMap<>();
        for (ProviderProduction provider : mapped.values()) {
            matchedByWeek.merge(provider.week(), 1, Integer::sum);
        }

        int snapshotsWritten = 0;
        if (persist) {
            // Revoke same-day coverage before writing anything. A failed refresh may leave harmless
            // partial production rows, but it cannot leave stale authorization to infer missing rows as zero.
            coverage.deleteBySeasonAsOf(season, SOURCE, asOfDate);
            for (ProviderProduction provider : mapped.values()) {
                ProviderStats stats = provider.stats();
                PlayerWeekProduction snapshot = provider.rawSchema() == RawScoringProduction.EXTENDED_SCHEMA_VERSION
                    ? PlayerWeekProduction.createExactScoringV2(
                        provider.localPlayerId(), season, provider.week(),
                        stats.passingYards(), stats.passingTouchdowns(), stats.interceptions(),
                        stats.rushingYards(), stats.rushingTouchdowns(), stats.receptions(),
                        stats.receivingYards(), stats.receivingTouchdowns(), stats.fumblesLost(),
                        stats.passingTwoPointConversions(), stats.rushingAttempts(),
                        stats.rushingTwoPointConversions(), stats.receivingTwoPointConversions(),
                        stats.fumbleRecoveryTouchdowns(), stats.specialTeamsTouchdowns(), SOURCE, asOfDate)
                    : PlayerWeekProduction.create(
                        provider.localPlayerId(), season, provider.week(),
                        stats.passingYards(), stats.passingTouchdowns(), stats.interceptions(),
                        stats.rushingYards(), stats.rushingTouchdowns(), stats.receptions(),
                        stats.receivingYards(), stats.receivingTouchdowns(), stats.fumblesLost(), SOURCE, asOfDate);
                production.save(snapshot);
                snapshotsWritten++;
            }

            for (var entry : providerByWeek.entrySet()) {
                int week = entry.getKey();
                coverage.replace(new PlayerWeekProductionCoverage(
                    season,
                    week,
                    SOURCE,
                    statsUri(season),
                    asOfDate,
                    entry.getValue(),
                    matchedByWeek.getOrDefault(week, 0),
                    unmatchedByWeek.getOrDefault(week, 0),
                    identityCovered));
            }
        }

        return new ImportResult(
            season,
            asOfDate,
            persist,
            rows.size(),
            requestedSeasonRows,
            regularSeasonRows,
            mapped.size(),
            unmatched.size(),
            excludedBlankPlayerRows,
            snapshotsWritten,
            List.copyOf(unmatched));
    }

    private static ProviderStats parseProviderStats(Map<String, String> row, int rawSchema, String id) {
        return new ProviderStats(
            parseSignedInt(value(row, "passing_yards"), "passing_yards", id),
            parseNonNegativeInt(value(row, "passing_tds"), "passing_tds", id),
            parseNonNegativeInt(value(row, "passing_interceptions"), "passing_interceptions", id),
            parseSignedInt(value(row, "rushing_yards"), "rushing_yards", id),
            parseNonNegativeInt(value(row, "rushing_tds"), "rushing_tds", id),
            parseNonNegativeInt(value(row, "receptions"), "receptions", id),
            parseSignedInt(value(row, "receiving_yards"), "receiving_yards", id),
            parseNonNegativeInt(value(row, "receiving_tds"), "receiving_tds", id),
            parseNonNegativeInt(value(row, "sack_fumbles_lost"), "sack_fumbles_lost", id)
                + parseNonNegativeInt(value(row, "rushing_fumbles_lost"), "rushing_fumbles_lost", id)
                + parseNonNegativeInt(value(row, "receiving_fumbles_lost"), "receiving_fumbles_lost", id),
            rawSchema == RawScoringProduction.EXTENDED_SCHEMA_VERSION
                ? parseNonNegativeInt(value(row, "passing_2pt_conversions"), "passing_2pt_conversions", id) : 0,
            rawSchema == RawScoringProduction.EXTENDED_SCHEMA_VERSION
                ? parseNonNegativeInt(value(row, "carries"), "carries", id) : 0,
            rawSchema == RawScoringProduction.EXTENDED_SCHEMA_VERSION
                ? parseNonNegativeInt(value(row, "rushing_2pt_conversions"), "rushing_2pt_conversions", id) : 0,
            rawSchema == RawScoringProduction.EXTENDED_SCHEMA_VERSION
                ? parseNonNegativeInt(value(row, "receiving_2pt_conversions"), "receiving_2pt_conversions", id) : 0,
            rawSchema == RawScoringProduction.EXTENDED_SCHEMA_VERSION
                ? parseNonNegativeInt(value(row, "fumble_recovery_tds"), "fumble_recovery_tds", id) : 0,
            rawSchema == RawScoringProduction.EXTENDED_SCHEMA_VERSION
                ? parseNonNegativeInt(value(row, "special_teams_tds"), "special_teams_tds", id) : 0);
    }

    private static int detectRawScoringSchema(Map<String, String> row) {
        long present = EXTENDED_COLUMNS.stream().filter(row::containsKey).count();
        if (present == 0) return RawScoringProduction.LEGACY_SCHEMA_VERSION;
        if (present == EXTENDED_COLUMNS.size()) return RawScoringProduction.EXTENDED_SCHEMA_VERSION;
        throw new IllegalArgumentException(
            "partial nflverse extended scoring schema; expected all columns " + EXTENDED_COLUMNS);
    }

    public static URI statsUri(int season) {
        requireSeason(season);
        return URI.create(
            "https://github.com/nflverse/nflverse-data/releases/download/stats_player/stats_player_week_"
                + season + ".csv");
    }

    private Map<String, String> buildLocalPlayerIndex() throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        for (var player : players.findAll()) {
            String sleeperId = normalizeId(player.getExternalId());
            if (sleeperId == null) continue;
            String existing = result.putIfAbsent(sleeperId, player.getId());
            if (existing != null && !existing.equals(player.getId())) {
                throw new IllegalArgumentException("ambiguous local players for Sleeper id: " + sleeperId);
            }
        }
        return result;
    }

    private static List<String> identityCoveredPlayers(
        Map<String, String> sleeperByGsis,
        Map<String, String> localBySleeper) {
        Set<String> sleeperIds = new HashSet<>(sleeperByGsis.values());
        return localBySleeper.entrySet().stream()
            .filter(entry -> sleeperIds.contains(entry.getKey()))
            .map(Map.Entry::getValue)
            .sorted()
            .toList();
    }

    private static Map<String, String> buildCrosswalk(List<Map<String, String>> rows) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String gsisId = normalizeId(row.get("gsis_id"));
            String sleeperId = normalizeId(row.get("sleeper_id"));
            if (gsisId == null || sleeperId == null) continue;
            String existing = result.putIfAbsent(gsisId, sleeperId);
            if (existing != null && !existing.equals(sleeperId)) {
                throw new IllegalArgumentException("ambiguous GSIS-to-Sleeper mapping for GSIS id: " + gsisId);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("player-id crosswalk contains no GSIS-to-Sleeper mappings");
        }
        return result;
    }

    private String download(URI uri, String description) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", "Butler-FF/0.1")
            .GET()
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(description + " unavailable (HTTP " + response.statusCode() + "): " + uri);
        }
        return response.body();
    }

    private static String required(Map<String, String> row, String column) {
        if (!row.containsKey(column)) throw new IllegalArgumentException("missing nflverse column: " + column);
        return row.get(column);
    }

    private static String value(Map<String, String> row, String column) {
        if (!row.containsKey(column)) throw new IllegalArgumentException("missing nflverse column: " + column);
        String value = row.get(column);
        return value == null || value.isBlank() || value.equalsIgnoreCase("NA") ? "0" : value;
    }

    private static int parsePositiveInt(String text, String field, String id) {
        int parsed = parseIntegralInt(text, field, id);
        if (parsed <= 0) throw new IllegalArgumentException("invalid nflverse " + field + " for " + id + ": " + text);
        return parsed;
    }

    private static int parseNonNegativeInt(String text, String field, String id) {
        int parsed = parseIntegralInt(text, field, id);
        if (parsed < 0) throw new IllegalArgumentException("invalid nflverse " + field + " for " + id + ": " + text);
        return parsed;
    }

    private static int parseSignedInt(String text, String field, String id) {
        return parseIntegralInt(text, field, id);
    }

    private static int parseIntegralInt(String text, String field, String id) {
        try {
            double parsed = Double.parseDouble(text.trim());
            if (!Double.isFinite(parsed)
                || parsed != Math.rint(parsed)
                || parsed < Integer.MIN_VALUE
                || parsed > Integer.MAX_VALUE) {
                throw new NumberFormatException();
            }
            return (int) parsed;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid nflverse " + field + " for " + id + ": " + text, e);
        }
    }

    private static String normalizeId(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("NA")) return null;
        String normalized = value.trim();
        if (normalized.matches("[0-9]+\\.0")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        return normalized;
    }

    private static void requireSeason(int season) {
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public record ImportResult(
        int season,
        LocalDate asOfDate,
        boolean persisted,
        int providerRows,
        int requestedSeasonRows,
        int regularSeasonRows,
        int matchedPlayerWeeks,
        int unmatchedProviderRows,
        int excludedBlankPlayerRows,
        int snapshotsWritten,
        List<UnmatchedProviderRow> unmatched) {

        public ImportResult {
            Objects.requireNonNull(asOfDate, "asOfDate must not be null");
            unmatched = List.copyOf(Objects.requireNonNull(unmatched, "unmatched must not be null"));
        }
    }

    public record UnmatchedProviderRow(String gsisId, String sleeperId, int week, String reason) {}

    private record PlayerWeekKey(String playerId, int week) {}

    private record ProviderProduction(
        String localPlayerId,
        String gsisId,
        String sleeperId,
        int week,
        ProviderStats stats,
        int rawSchema) {}

    private record ProviderStats(
        int passingYards,
        int passingTouchdowns,
        int interceptions,
        int rushingYards,
        int rushingTouchdowns,
        int receptions,
        int receivingYards,
        int receivingTouchdowns,
        int fumblesLost,
        int passingTwoPointConversions,
        int rushingAttempts,
        int rushingTwoPointConversions,
        int receivingTwoPointConversions,
        int fumbleRecoveryTouchdowns,
        int specialTeamsTouchdowns) {

        boolean isZero() {
            return passingYards == 0
                && passingTouchdowns == 0
                && interceptions == 0
                && rushingYards == 0
                && rushingTouchdowns == 0
                && receptions == 0
                && receivingYards == 0
                && receivingTouchdowns == 0
                && fumblesLost == 0
                && passingTwoPointConversions == 0
                && rushingAttempts == 0
                && rushingTwoPointConversions == 0
                && receivingTwoPointConversions == 0
                && fumbleRecoveryTouchdowns == 0
                && specialTeamsTouchdowns == 0;
        }
    }

    private static final class Csv {
        private Csv() {}

        static List<Map<String, String>> parse(String csv) {
            List<List<String>> rows = rows(csv);
            if (rows.isEmpty()) return List.of();
            List<String> header = rows.getFirst();
            List<Map<String, String>> result = new ArrayList<>();
            for (int i = 1; i < rows.size(); i++) {
                List<String> values = rows.get(i);
                if (values.size() == 1 && values.getFirst().isBlank()) continue;
                Map<String, String> row = new LinkedHashMap<>();
                for (int j = 0; j < header.size(); j++) {
                    row.put(header.get(j).trim(), j < values.size() ? values.get(j) : "");
                }
                result.add(row);
            }
            return result;
        }

        private static List<List<String>> rows(String csv) {
            List<List<String>> result = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < csv.length(); i++) {
                char c = csv.charAt(i);
                if (c == '"') {
                    if (quoted && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (c == ',' && !quoted) {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else if ((c == '\n' || c == '\r') && !quoted) {
                    if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                    row.add(cell.toString());
                    cell.setLength(0);
                    result.add(row);
                    row = new ArrayList<>();
                } else {
                    cell.append(c);
                }
            }
            if (cell.length() > 0 || !row.isEmpty()) {
                row.add(cell.toString());
                result.add(row);
            }
            return result;
        }
    }
}
