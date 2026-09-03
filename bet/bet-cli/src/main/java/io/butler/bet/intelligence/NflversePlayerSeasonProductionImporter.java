package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerSeasonProduction;

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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Imports raw regular-season production from nflverse using exact GSIS-to-Sleeper identity mapping. */
public final class NflversePlayerSeasonProductionImporter {
    public static final String SOURCE = "nflverse";
    public static final URI PLAYER_IDS_URI = URI.create(
        "https://raw.githubusercontent.com/dynastyprocess/data/master/files/db_playerids.csv");

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
        return fetchAndProcess(season, true);
    }

    public ImportResult preview(int season) throws IOException, InterruptedException, SQLException {
        return fetchAndProcess(season, false);
    }

    private ImportResult fetchAndProcess(int season, boolean persist) throws IOException, InterruptedException, SQLException {
        requireSeason(season);
        String statsCsv = download(statsUri(season), "nflverse player stats for " + season);
        String idsCsv = download(PLAYER_IDS_URI, "fantasy player id crosswalk");
        return processCsv(season, statsCsv, idsCsv, LocalDate.now(), persist);
    }

    public ImportResult importCsv(int season, String statsCsv, String idsCsv, LocalDate asOfDate) throws SQLException {
        return processCsv(season, statsCsv, idsCsv, asOfDate, true);
    }

    public ImportResult previewCsv(int season, String statsCsv, String idsCsv, LocalDate asOfDate) throws SQLException {
        return processCsv(season, statsCsv, idsCsv, asOfDate, false);
    }

    private ImportResult processCsv(int season, String statsCsv, String idsCsv, LocalDate asOfDate, boolean persist)
        throws SQLException {
        requireSeason(season);
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        List<Map<String, String>> statsRows = Csv.parse(requireText(statsCsv, "statsCsv"));
        List<Map<String, String>> idRows = Csv.parse(requireText(idsCsv, "idsCsv"));
        if (statsRows.isEmpty()) throw new IllegalArgumentException("nflverse stats contain no data rows");
        if (idRows.isEmpty()) throw new IllegalArgumentException("player-id crosswalk contains no data rows");

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
            providerRowsMapped++;

            ProviderProduction provider = new ProviderProduction(
                gsisId, sleeperId,
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
                    + parseNonNegativeInt(value(row, "receiving_fumbles_lost"), "receiving_fumbles_lost", gsisId));
            ProviderProduction existing = bySleeper.putIfAbsent(sleeperId, provider);
            if (existing != null && !existing.equals(provider)) {
                throw new IllegalArgumentException("ambiguous nflverse production mapping for Sleeper id: " + sleeperId);
            }
        }
        if (providerRowsForSeason == 0) throw new IllegalArgumentException("nflverse stats contain no rows for season: " + season);

        List<UnmatchedPlayer> unmatched = new ArrayList<>();
        int eligiblePlayers = 0;
        int matchedPlayers = 0;
        int snapshotsWritten = 0;
        for (Player player : players.findAll()) {
            String sleeperId = normalizeId(player.getExternalId());
            if (sleeperId == null) continue;
            eligiblePlayers++;
            ProviderProduction provider = bySleeper.get(sleeperId);
            if (provider == null) {
                unmatched.add(new UnmatchedPlayer(player.getId(), sleeperId, player.getDisplayName()));
                continue;
            }
            matchedPlayers++;
            if (persist) {
                PlayerSeasonProduction snapshot = PlayerSeasonProduction.create(
                    player.getId(), season, provider.gamesPlayed(), provider.passingYards(), provider.passingTouchdowns(),
                    provider.interceptions(), provider.rushingYards(), provider.rushingTouchdowns(), provider.receptions(),
                    provider.receivingYards(), provider.receivingTouchdowns(), provider.fumblesLost(), SOURCE, asOfDate);
                production.save(snapshot);
                snapshotsWritten++;
            }
        }

        return new ImportResult(season, asOfDate, persist, statsRows.size(), providerRowsForSeason, sleeperByGsis.size(),
            providerRowsMapped, eligiblePlayers, matchedPlayers, unmatched.size(), snapshotsWritten, List.copyOf(unmatched));
    }

    public static URI statsUri(int season) {
        requireSeason(season);
        return URI.create("https://github.com/nflverse/nflverse-data/releases/download/stats_player/stats_player_reg_" + season + ".csv");
    }

    private Map<String, String> buildCrosswalk(List<Map<String, String>> rows) {
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
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new NumberFormatException();
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
        if (season < 1999 || season > 2100) throw new IllegalArgumentException("season must be between 1999 and 2100");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public record ImportResult(int season, LocalDate asOfDate, boolean persisted, int providerRows,
                               int providerRowsForSeason, int crosswalkEntries, int providerRowsMapped,
                               int eligiblePlayers, int matchedPlayers, int unmatchedPlayers,
                               int snapshotsWritten, List<UnmatchedPlayer> unmatched) {}
    public record UnmatchedPlayer(String playerId, String sleeperId, String playerName) {}

    private record ProviderProduction(String gsisId, String sleeperId, int gamesPlayed, int passingYards,
                                      int passingTouchdowns, int interceptions, int rushingYards,
                                      int rushingTouchdowns, int receptions, int receivingYards,
                                      int receivingTouchdowns, int fumblesLost) {}

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
                for (int j = 0; j < header.size(); j++) row.put(header.get(j).trim(), j < values.size() ? values.get(j) : "");
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
                    if (quoted && i + 1 < csv.length() && csv.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else quoted = !quoted;
                } else if (c == ',' && !quoted) { row.add(cell.toString()); cell.setLength(0); }
                else if ((c == '\n' || c == '\r') && !quoted) {
                    if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                    row.add(cell.toString()); cell.setLength(0); rows.add(row); row = new ArrayList<>();
                } else cell.append(c);
            }
            if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
            return rows;
        }
    }
}
