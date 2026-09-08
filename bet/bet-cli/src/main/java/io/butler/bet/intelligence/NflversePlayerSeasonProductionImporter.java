package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerSeasonProduction;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Imports raw regular-season production from nflverse using exact GSIS-to-Sleeper identity mapping. */
public final class NflversePlayerSeasonProductionImporter {
    public static final String SOURCE = "nflverse";
    public static final URI PLAYER_IDS_URI = URI.create(
        "https://raw.githubusercontent.com/dynastyprocess/data/master/files/db_playerids.csv");
    private static final Set<String> EXTENDED_COLUMNS = Set.of(
        "passing_2pt_conversions", "carries", "rushing_2pt_conversions",
        "receiving_2pt_conversions", "fumble_recovery_tds", "special_teams_tds");

    private final PlayerRepository players;
    private final PlayerSeasonProductionRepository production;
    private final HttpClient http;

    public NflversePlayerSeasonProductionImporter(Database database) {
        this(database, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build());
    }

    NflversePlayerSeasonProductionImporter(Database database, HttpClient http) {
        Objects.requireNonNull(database, "database must not be null");
        this.players = new PlayerRepository(database);
        this.production = new PlayerSeasonProductionRepository(database);
        this.http = Objects.requireNonNull(http, "http must not be null");
    }

    public ImportResult refresh(int season) throws IOException, InterruptedException, SQLException {
        return fetchAndProcess(season, true, null);
    }

    public ImportResult preview(int season) throws IOException, InterruptedException, SQLException {
        return fetchAndProcess(season, false, null);
    }

    /**
     * Runs the normal exact nflverse identity pipeline while limiting Butler-player reconciliation
     * and production writes to the supplied exact Sleeper ids.
     */
    public ImportResult refreshForSleeperIds(int season, Set<String> sleeperIds)
        throws IOException, InterruptedException, SQLException {
        return fetchAndProcess(season, true, normalizeTargets(sleeperIds));
    }

    public ImportResult previewForSleeperIds(int season, Set<String> sleeperIds)
        throws IOException, InterruptedException, SQLException {
        return fetchAndProcess(season, false, normalizeTargets(sleeperIds));
    }

    private ImportResult fetchAndProcess(int season, boolean persist, Set<String> targetSleeperIds)
        throws IOException, InterruptedException, SQLException {
        requireSeason(season);
        return processCsv(
            season,
            download(statsUri(season), "nflverse player stats for " + season),
            download(PLAYER_IDS_URI, "fantasy player id crosswalk"),
            LocalDate.now(),
            persist,
            targetSleeperIds);
    }

    public ImportResult importCsv(int season, String statsCsv, String idsCsv, LocalDate asOfDate)
        throws SQLException {
        return processCsv(season, statsCsv, idsCsv, asOfDate, true, null);
    }

    public ImportResult previewCsv(int season, String statsCsv, String idsCsv, LocalDate asOfDate)
        throws SQLException {
        return processCsv(season, statsCsv, idsCsv, asOfDate, false, null);
    }

    public ImportResult importCsvForSleeperIds(
        int season,
        String statsCsv,
        String idsCsv,
        LocalDate asOfDate,
        Set<String> sleeperIds) throws SQLException {
        return processCsv(season, statsCsv, idsCsv, asOfDate, true, normalizeTargets(sleeperIds));
    }

    public ImportResult previewCsvForSleeperIds(
        int season,
        String statsCsv,
        String idsCsv,
        LocalDate asOfDate,
        Set<String> sleeperIds) throws SQLException {
        return processCsv(season, statsCsv, idsCsv, asOfDate, false, normalizeTargets(sleeperIds));
    }

    private ImportResult processCsv(
        int season,
        String statsCsv,
        String idsCsv,
        LocalDate asOfDate,
        boolean persist,
        Set<String> targetSleeperIds) throws SQLException {
        requireSeason(season);
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        List<Map<String, String>> statsRows = Csv.parse(requireText(statsCsv, "statsCsv"));
        List<Map<String, String>> idRows = Csv.parse(requireText(idsCsv, "idsCsv"));
        if (statsRows.isEmpty()) throw new IllegalArgumentException("nflverse stats contain no data rows");
        if (idRows.isEmpty()) throw new IllegalArgumentException("player-id crosswalk contains no data rows");

        int rawSchema = detectRawScoringSchema(statsRows.getFirst());
        Map<String, String> sleeperByGsis = buildCrosswalk(idRows);
        Map<String, ProviderProduction> bySleeper = new LinkedHashMap<>();
        int providerRowsForSeason = 0;
        int providerRowsMapped = 0;

        for (Map<String, String> row : statsRows) {
            int rowSeason = parseNonNegativeInt(required(row, "season"), "season", "provider row");
            if (rowSeason != season) continue;
            providerRowsForSeason++;
            String gsisId = normalizeId(required(row, "player_id"));
            String sleeperId = sleeperByGsis.get(gsisId);
            if (sleeperId == null) continue;
            if (targetSleeperIds != null && !targetSleeperIds.contains(sleeperId)) continue;
            providerRowsMapped++;

            ProviderProduction provider = new ProviderProduction(
                gsisId,
                sleeperId,
                parseNonNegativeInt(value(row, "games"), "games", gsisId),
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
                    + parseNonNegativeInt(value(row, "receiving_fumbles_lost"), "receiving_fumbles_lost", gsisId),
                rawSchema == 2 ? parseNonNegativeInt(value(row, "passing_2pt_conversions"), "passing_2pt_conversions", gsisId) : 0,
                rawSchema == 2 ? parseNonNegativeInt(value(row, "carries"), "carries", gsisId) : 0,
                rawSchema == 2 ? parseNonNegativeInt(value(row, "rushing_2pt_conversions"), "rushing_2pt_conversions", gsisId) : 0,
                rawSchema == 2 ? parseNonNegativeInt(value(row, "receiving_2pt_conversions"), "receiving_2pt_conversions", gsisId) : 0,
                rawSchema == 2 ? parseNonNegativeInt(value(row, "fumble_recovery_tds"), "fumble_recovery_tds", gsisId) : 0,
                rawSchema == 2 ? parseNonNegativeInt(value(row, "special_teams_tds"), "special_teams_tds", gsisId) : 0,
                rawSchema);
            ProviderProduction previous = bySleeper.putIfAbsent(sleeperId, provider);
            if (previous != null && !previous.equals(provider)) {
                throw new IllegalArgumentException(
                    "ambiguous nflverse production mapping for Sleeper id: " + sleeperId);
            }
        }

        if (providerRowsForSeason == 0) {
            throw new IllegalArgumentException("nflverse stats contain no rows for season: " + season);
        }

        List<UnmatchedPlayer> unmatched = new ArrayList<>();
        int eligiblePlayers = 0;
        int matchedPlayers = 0;
        int snapshotsWritten = 0;
        for (Player player : players.findAll()) {
            String sleeperId = normalizeId(player.getExternalId());
            if (sleeperId == null) continue;
            if (targetSleeperIds != null && !targetSleeperIds.contains(sleeperId)) continue;
            eligiblePlayers++;
            ProviderProduction provider = bySleeper.get(sleeperId);
            if (provider == null) {
                unmatched.add(new UnmatchedPlayer(player.getId(), sleeperId, player.getDisplayName()));
                continue;
            }
            matchedPlayers++;
            if (persist) {
                PlayerSeasonProduction snapshot = provider.rawSchema() == RawScoringProduction.EXTENDED_SCHEMA_VERSION
                    ? PlayerSeasonProduction.createExactScoringV2(
                        player.getId(), season, provider.gamesPlayed(), provider.passingYards(),
                        provider.passingTouchdowns(), provider.interceptions(), provider.rushingYards(),
                        provider.rushingTouchdowns(), provider.receptions(), provider.receivingYards(),
                        provider.receivingTouchdowns(), provider.fumblesLost(), provider.passingTwoPointConversions(),
                        provider.rushingAttempts(), provider.rushingTwoPointConversions(),
                        provider.receivingTwoPointConversions(), provider.fumbleRecoveryTouchdowns(),
                        provider.specialTeamsTouchdowns(), SOURCE, asOfDate)
                    : PlayerSeasonProduction.create(
                        player.getId(), season, provider.gamesPlayed(), provider.passingYards(),
                        provider.passingTouchdowns(), provider.interceptions(), provider.rushingYards(),
                        provider.rushingTouchdowns(), provider.receptions(), provider.receivingYards(),
                        provider.receivingTouchdowns(), provider.fumblesLost(), SOURCE, asOfDate);
                production.save(snapshot);
                snapshotsWritten++;
            }
        }

        return new ImportResult(
            season,
            asOfDate,
            persist,
            statsRows.size(),
            providerRowsForSeason,
            sleeperByGsis.size(),
            providerRowsMapped,
            eligiblePlayers,
            matchedPlayers,
            unmatched.size(),
            snapshotsWritten,
            List.copyOf(unmatched));
    }

    private static Set<String> normalizeTargets(Set<String> sleeperIds) {
        Objects.requireNonNull(sleeperIds, "sleeperIds must not be null");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String sleeperId : sleeperIds) {
            String value = normalizeId(sleeperId);
            if (value == null) throw new IllegalArgumentException("target Sleeper id must not be blank");
            normalized.add(value);
        }
        if (normalized.isEmpty()) throw new IllegalArgumentException("target Sleeper ids must not be empty");
        return Set.copyOf(normalized);
    }

    private static int detectRawScoringSchema(Map<String, String> row) {
        long present = EXTENDED_COLUMNS.stream().filter(row::containsKey).count();
        if (present == 0) return RawScoringProduction.LEGACY_SCHEMA_VERSION;
        if (present == EXTENDED_COLUMNS.size()) return RawScoringProduction.EXTENDED_SCHEMA_VERSION;
        throw new IllegalArgumentException("partial nflverse extended scoring schema; expected all columns " + EXTENDED_COLUMNS);
    }

    public static URI statsUri(int season) {
        requireSeason(season);
        return URI.create(
            "https://github.com/nflverse/nflverse-data/releases/download/stats_player/stats_player_reg_"
                + season + ".csv");
    }

    private Map<String, String> buildCrosswalk(List<Map<String, String>> rows) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String gsis = normalizeId(row.get("gsis_id"));
            String sleeper = normalizeId(row.get("sleeper_id"));
            if (gsis == null || sleeper == null) continue;
            String previous = result.putIfAbsent(gsis, sleeper);
            if (previous != null && !previous.equals(sleeper)) {
                throw new IllegalArgumentException("ambiguous GSIS-to-Sleeper mapping for GSIS id: " + gsis);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("player-id crosswalk contains no GSIS-to-Sleeper mappings");
        }
        return result;
    }

    private String download(URI uri, String description) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Butler-FF/0.1")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
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
            double value = Double.parseDouble(text.trim());
            if (!Double.isFinite(value) || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new NumberFormatException();
            }
            return (int) value;
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
        int providerRowsForSeason,
        int crosswalkEntries,
        int providerRowsMapped,
        int eligiblePlayers,
        int matchedPlayers,
        int unmatchedPlayers,
        int snapshotsWritten,
        List<UnmatchedPlayer> unmatched) {}

    public record UnmatchedPlayer(String playerId, String sleeperId, String playerName) {}

    private record ProviderProduction(
        String gsisId,
        String sleeperId,
        int gamesPlayed,
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
        int specialTeamsTouchdowns,
        int rawSchema) {}

    private static final class Csv {
        private Csv() {}

        static List<Map<String, String>> parse(String csv) {
            List<List<String>> rows = rows(csv);
            if (rows.isEmpty()) return List.of();
            List<String> header = rows.getFirst();
            List<Map<String, String>> out = new ArrayList<>();
            for (int i = 1; i < rows.size(); i++) {
                List<String> values = rows.get(i);
                if (values.size() == 1 && values.getFirst().isBlank()) continue;
                Map<String, String> row = new LinkedHashMap<>();
                for (int j = 0; j < header.size(); j++) {
                    row.put(header.get(j).trim(), j < values.size() ? values.get(j) : "");
                }
                out.add(row);
            }
            return out;
        }

        private static List<List<String>> rows(String csv) {
            List<List<String>> out = new ArrayList<>();
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
                    out.add(row);
                    row = new ArrayList<>();
                } else {
                    cell.append(c);
                }
            }
            if (cell.length() > 0 || !row.isEmpty()) {
                row.add(cell.toString());
                out.add(row);
            }
            return out;
        }
    }
}
