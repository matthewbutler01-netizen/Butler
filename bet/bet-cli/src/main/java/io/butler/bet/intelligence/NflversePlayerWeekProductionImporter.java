package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerWeekProductionCoverageRepository;
import io.butler.bet.data.PlayerWeekProductionRepository;
import io.butler.bet.domain.PlayerWeekProduction;
import io.butler.bet.domain.PlayerWeekProductionCoverage;

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

    public ImportResult importCsv(int season, String statsCsv, String idsCsv, LocalDate asOfDate) throws SQLException {
        return processCsv(season, statsCsv, idsCsv, asOfDate, true);
    }

    public ImportResult previewCsv(int season, String statsCsv, String idsCsv, LocalDate asOfDate) throws SQLException {
        return processCsv(season, statsCsv, idsCsv, asOfDate, false);
    }

    private ImportResult fetchAndProcess(int season, boolean persist)
        throws IOException, InterruptedException, SQLException {
        requireSeason(season);
        String statsCsv = download(statsUri(season), "nflverse weekly player stats for " + season);
        String idsCsv = download(NflversePlayerSeasonProductionImporter.PLAYER_IDS_URI, "fantasy player id crosswalk");
        return processCsv(season, statsCsv, idsCsv, LocalDate.now(), persist);
    }

    private ImportResult processCsv(int season, String statsCsv, String idsCsv, LocalDate asOfDate, boolean persist)
        throws SQLException {
        requireSeason(season);
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        List<Map<String, String>> statsRows = Csv.parse(requireText(statsCsv, "statsCsv"));
        List<Map<String, String>> idRows = Csv.parse(requireText(idsCsv, "idsCsv"));
        if (statsRows.isEmpty()) throw new IllegalArgumentException("nflverse weekly stats contain no data rows");
        if (idRows.isEmpty()) throw new IllegalArgumentException("player-id crosswalk contains no data rows");

        Map<String, String> sleeperByGsis = buildCrosswalk(idRows);
        Map<String, String> localPlayerBySleeper = buildLocalPlayerIndex();
        List<String> identityCoveredPlayerIds = identityCoveredPlayers(sleeperByGsis, localPlayerBySleeper);
        Map<PlayerWeekKey, ProviderProduction> mapped = new LinkedHashMap<>();
        List<UnmatchedProviderRow> unmatched = new ArrayList<>();
        Map<Integer, Integer> providerRowsByWeek = new LinkedHashMap<>();
        Map<Integer, Integer> unmatchedRowsByWeek = new LinkedHashMap<>();
        int requestedSeasonRows = 0;
        int regularSeasonRows = 0;

        for (Map<String, String> row : statsRows) {
            int rowSeason = parseNonNegativeInt(required(row, "season"), "season", "provider row");
            if (rowSeason != season) continue;
            requestedSeasonRows++;
            String seasonType = requireText(required(row, "season_type"), "season_type").trim();
            if (!"REG".equalsIgnoreCase(seasonType)) continue;
            regularSeasonRows++;

            int week = parsePositiveInt(required(row, "week"), "week", "provider row");
            providerRowsByWeek.merge(week, 1, Integer::sum);
            String gsisId = normalizeId(required(row, "player_id"));
            if (gsisId == null) throw new IllegalArgumentException("blank nflverse player_id for season " + season + " week " + week);
            String sleeperId = sleeperByGsis.get(gsisId);
            if (sleeperId == null) {
                unmatched.add(new UnmatchedProviderRow(gsisId, null, week, "No GSIS-to-Sleeper mapping"));
                unmatchedRowsByWeek.merge(week, 1, Integer::sum);
                continue;
            }
            String localPlayerId = localPlayerBySleeper.get(sleeperId);
            if (localPlayerId == null) {
                unmatched.add(new UnmatchedProviderRow(gsisId, sleeperId, week, "No local player for Sleeper id"));
                unmatchedRowsByWeek.merge(week, 1, Integer::sum);
                continue;
            }

            ProviderProduction provider = new ProviderProduction(
                localPlayerId, gsisId, sleeperId, week,
                parseSignedInt(value(row, "passing_yards"), "passing_yards", gsisId),
                parseNonNegativeInt(value(row, "passing_tds"), "passing_tds", gsisId),
                parseNonNegativeInt(value(row, "passing_interceptions"), "passing_interceptions", gsisId),
                parseSignedInt(value(row, "rushing_yards"), "rushing_yards", gsisId),
                parseNonNegativeInt(value(row, "rushing_tds"), "rushing_tds", gsisId),
                parseNonNegativeInt(value(row, "receptions"), "receptions", gsisId),
                parseSignedInt(value(row, "receiving_yards"), "receiving_yards", gsisId),
                parseNonNegativeInt(value(row, "receiving_tds"), "receiving_tds", gsisId),
                parseNonNegativeInt(value(row, "sack_fumbles_lost"), "sack_fumbles_lost", gsisId)
                    + parseNonNegativeInt(value(row, "rushing_fumbles_lost"), "rushing_fumbles_lost", gsisId)
                    + parseNonNegativeInt(value(row, "receiving_fumbles_lost"), "receiving_fumbles_lost", gsisId));
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

        Map<Integer, Integer> matchedRowsByWeek = new LinkedHashMap<>();
        for (ProviderProduction provider : mapped.values()) {
            matchedRowsByWeek.merge(provider.week(), 1, Integer::sum);
        }

        int snapshotsWritten = 0;
        if (persist) {
            // Revoke same-day coverage before writing anything. A failed refresh may leave harmless
            // partial production rows, but it cannot leave stale authorization to infer missing rows as zero.
            coverage.deleteBySeasonAsOf(season, SOURCE, asOfDate);
            for (ProviderProduction provider : mapped.values()) {
                production.save(PlayerWeekProduction.create(
                    provider.localPlayerId(), season, provider.week(), provider.passingYards(),
                    provider.passingTouchdowns(), provider.interceptions(), provider.rushingYards(),
                    provider.rushingTouchdowns(), provider.receptions(), provider.receivingYards(),
                    provider.receivingTouchdowns(), provider.fumblesLost(), SOURCE, asOfDate));
                snapshotsWritten++;
            }
            for (var entry : providerRowsByWeek.entrySet()) {
                int week = entry.getKey();
                coverage.replace(new PlayerWeekProductionCoverage(
                    season,
                    week,
                    SOURCE,
                    statsUri(season),
                    asOfDate,
                    entry.getValue(),
                    matchedRowsByWeek.getOrDefault(week, 0),
                    unmatchedRowsByWeek.getOrDefault(week, 0),
                    identityCoveredPlayerIds));
            }
        }

        return new ImportResult(season, asOfDate, persist, statsRows.size(), requestedSeasonRows,
            regularSeasonRows, mapped.size(), unmatched.size(), snapshotsWritten, List.copyOf(unmatched));
    }

    public static URI statsUri(int season) {
        requireSeason(season);
        return URI.create("https://github.com/nflverse/nflverse-data/releases/download/stats_player/stats_player_week_"
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

    private static List<String> identityCoveredPlayers(Map<String, String> sleeperByGsis,
                                                       Map<String, String> localPlayerBySleeper) {
        Set<String> sleeperIdsInCrosswalk = new HashSet<>(sleeperByGsis.values());
        return localPlayerBySleeper.entrySet().stream()
            .filter(entry -> sleeperIdsInCrosswalk.contains(entry.getKey()))
            .map(Map.Entry::getValue)
            .sorted()
            .toList();
    }

    private static Map<String, String> buildCrosswalk(List<Map<String, String>> rows) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String gsis = normalizeId(row.get("gsis_id"));
            String sleeper = normalizeId(row.get("sleeper_id"));
            if (gsis == null || sleeper == null) continue;
            String existing = result.putIfAbsent(gsis, sleeper);
            if (existing != null && !existing.equals(sleeper)) {
                throw new IllegalArgumentException("ambiguous GSIS-to-Sleeper mapping for GSIS id: " + gsis);
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("player-id crosswalk contains no GSIS-to-Sleeper mappings");
        return result;
    }

    private String download(URI uri, String description) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
            .header("User-Agent", "Butler-FF/0.1").GET().build();
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
            if (!Double.isFinite(parsed) || parsed != Math.rint(parsed)
                || parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) throw new NumberFormatException();
            return (int) parsed;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid nflverse " + field + " for " + id + ": " + text, e);
        }
    }

    private static String normalizeId(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("NA")) return null;
        String normalized = value.trim();
        if (normalized.matches("[0-9]+\\.0")) normalized = normalized.substring(0, normalized.length() - 2);
        return normalized;
    }

    private static void requireSeason(int season) {
        if (season < 1999 || season > 2100) throw new IllegalArgumentException("season must be between 1999 and 2100");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public record ImportResult(int season, LocalDate asOfDate, boolean persisted, int providerRows,
                               int requestedSeasonRows, int regularSeasonRows, int matchedPlayerWeeks,
                               int unmatchedProviderRows, int snapshotsWritten,
                               List<UnmatchedProviderRow> unmatched) {
        public ImportResult {
            Objects.requireNonNull(asOfDate, "asOfDate must not be null");
            unmatched = List.copyOf(Objects.requireNonNull(unmatched, "unmatched must not be null"));
        }
    }

    public record UnmatchedProviderRow(String gsisId, String sleeperId, int week, String reason) {}
    private record PlayerWeekKey(String playerId, int week) {}
    private record ProviderProduction(String localPlayerId, String gsisId, String sleeperId, int week,
                                      int passingYards, int passingTouchdowns, int interceptions,
                                      int rushingYards, int rushingTouchdowns, int receptions,
                                      int receivingYards, int receivingTouchdowns, int fumblesLost) {}

    private static final class Csv {
        private Csv() {}

        static List<Map<String, String>> parse(String csv) {
            List<List<String>> rows = rows(csv);
            if (rows.isEmpty()) return List.of();
            List<String> header = rows.get(0);
            List<Map<String, String>> result = new ArrayList<>();
            for (int i = 1; i < rows.size(); i++) {
                List<String> values = rows.get(i);
                if (values.size() == 1 && values.get(0).isBlank()) continue;
                Map<String, String> row = new LinkedHashMap<>();
                for (int j = 0; j < header.size(); j++) {
                    row.put(header.get(j).trim(), j < values.size() ? values.get(j) : "");
                }
                result.add(row);
            }
            return result;
        }

        private static List<List<String>> rows(String csv) {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < csv.length(); i++) {
                char c = csv.charAt(i);
                if (c == '"') {
                    if (quoted && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else quoted = !quoted;
                } else if (c == ',' && !quoted) {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else if ((c == '\n' || c == '\r') && !quoted) {
                    if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                    row.add(cell.toString());
                    cell.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                } else cell.append(c);
            }
            if (cell.length() > 0 || !row.isEmpty()) {
                row.add(cell.toString());
                rows.add(row);
            }
            return rows;
        }
    }
}
