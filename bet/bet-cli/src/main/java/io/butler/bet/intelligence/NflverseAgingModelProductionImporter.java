package io.butler.bet.intelligence;

import io.butler.bet.data.AgingModelPlayerSeasonProductionRepository;
import io.butler.bet.data.Database;
import io.butler.bet.domain.AgingModelPlayerSeasonProduction;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Imports broad GSIS-keyed nflverse production history for model training, without fantasy-roster filtering. */
public final class NflverseAgingModelProductionImporter {
    public static final String SOURCE = "nflverse";

    private final AgingModelPlayerSeasonProductionRepository production;
    private final Downloader downloader;
    private final Supplier<LocalDate> today;

    public NflverseAgingModelProductionImporter(Database database) {
        this(database, httpDownloader(), () -> LocalDate.now(ZoneOffset.UTC));
    }

    NflverseAgingModelProductionImporter(Database database, Downloader downloader, Supplier<LocalDate> today) {
        Objects.requireNonNull(database, "database must not be null");
        this.production = new AgingModelPlayerSeasonProductionRepository(database);
        this.downloader = Objects.requireNonNull(downloader, "downloader must not be null");
        this.today = Objects.requireNonNull(today, "today must not be null");
    }

    public HistoryImportResult preview(int startSeason, int endSeason)
        throws InterruptedException, SQLException {
        return process(startSeason, endSeason, false);
    }

    public HistoryImportResult refresh(int startSeason, int endSeason)
        throws InterruptedException, SQLException {
        return process(startSeason, endSeason, true);
    }

    public SeasonImportResult importCsv(int season, String csv, LocalDate asOfDate) throws SQLException {
        return processCsv(season, csv, asOfDate, true);
    }

    public SeasonImportResult previewCsv(int season, String csv, LocalDate asOfDate) throws SQLException {
        return processCsv(season, csv, asOfDate, false);
    }

    private HistoryImportResult process(int startSeason, int endSeason, boolean persist)
        throws InterruptedException, SQLException {
        requireRange(startSeason, endSeason);
        LocalDate asOfDate = Objects.requireNonNull(today.get(), "today supplier returned null");
        List<SeasonImportResult> successes = new ArrayList<>();
        List<SeasonFailure> failures = new ArrayList<>();
        for (int season = startSeason; season <= endSeason; season++) {
            try {
                String csv = downloader.download(NflversePlayerSeasonProductionImporter.statsUri(season),
                    "nflverse player stats for " + season);
                successes.add(processCsv(season, csv, asOfDate, persist));
            } catch (IOException e) {
                failures.add(new SeasonFailure(season, FailureType.DOWNLOAD, message(e)));
            } catch (IllegalArgumentException e) {
                failures.add(new SeasonFailure(season, FailureType.VALIDATION, message(e)));
            }
        }
        return new HistoryImportResult(startSeason, endSeason, asOfDate, persist,
            List.copyOf(successes), List.copyOf(failures));
    }

    private SeasonImportResult processCsv(int season, String csv, LocalDate asOfDate, boolean persist)
        throws SQLException {
        requireSeason(season);
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        List<Map<String, String>> rows = Csv.parse(requireText(csv, "csv"));
        if (rows.isEmpty()) throw new IllegalArgumentException("nflverse stats contain no data rows");

        Map<String, AgingModelPlayerSeasonProduction> byGsis = new LinkedHashMap<>();
        int providerRowsForSeason = 0;
        for (Map<String, String> row : rows) {
            int rowSeason = parseInt(required(row, "season"), "season", "provider row");
            if (rowSeason != season) continue;
            providerRowsForSeason++;
            String gsis = normalizeId(required(row, "player_id"));
            if (gsis == null) continue;
            AgingModelPlayerSeasonProduction value = new AgingModelPlayerSeasonProduction(
                gsis, season,
                parseInt(value(row, "games"), "games", gsis),
                parseInt(value(row, "passing_yards"), "passing_yards", gsis),
                parseInt(value(row, "passing_tds"), "passing_tds", gsis),
                parseInt(value(row, "passing_interceptions"), "passing_interceptions", gsis),
                parseInt(value(row, "rushing_yards"), "rushing_yards", gsis),
                parseInt(value(row, "rushing_tds"), "rushing_tds", gsis),
                parseInt(value(row, "receptions"), "receptions", gsis),
                parseInt(value(row, "receiving_yards"), "receiving_yards", gsis),
                parseInt(value(row, "receiving_tds"), "receiving_tds", gsis),
                parseInt(value(row, "sack_fumbles_lost"), "sack_fumbles_lost", gsis)
                    + parseInt(value(row, "rushing_fumbles_lost"), "rushing_fumbles_lost", gsis)
                    + parseInt(value(row, "receiving_fumbles_lost"), "receiving_fumbles_lost", gsis),
                SOURCE, asOfDate);
            AgingModelPlayerSeasonProduction existing = byGsis.putIfAbsent(gsis, value);
            if (existing != null && !existing.equals(value)) {
                throw new IllegalArgumentException("conflicting nflverse production rows for GSIS id: " + gsis
                    + " season: " + season);
            }
        }
        if (providerRowsForSeason == 0) {
            throw new IllegalArgumentException("nflverse stats contain no rows for season: " + season);
        }

        int written = 0;
        if (persist) {
            for (AgingModelPlayerSeasonProduction value : byGsis.values()) {
                production.save(value);
                written++;
            }
        }
        int zeroGame = (int) byGsis.values().stream().filter(v -> v.gamesPlayed() == 0).count();
        return new SeasonImportResult(season, asOfDate, persist, rows.size(), providerRowsForSeason,
            byGsis.size(), zeroGame, written);
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

    private static int parseInt(String text, String field, String id) {
        try {
            double value = Double.parseDouble(text.trim());
            if (!Double.isFinite(value) || value < 0 || value != Math.rint(value) || value > Integer.MAX_VALUE) {
                throw new NumberFormatException();
            }
            return (int) value;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid nflverse " + field + " for " + id + ": " + text, e);
        }
    }

    private static String normalizeId(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("NA")) return null;
        return value.trim();
    }

    private static void requireSeason(int season) {
        if (season < 1999 || season > 2100) throw new IllegalArgumentException("season must be between 1999 and 2100");
    }

    private static void requireRange(int startSeason, int endSeason) {
        requireSeason(startSeason);
        requireSeason(endSeason);
        if (startSeason > endSeason) throw new IllegalArgumentException("start season must be <= end season");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static Downloader httpDownloader() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        return (uri, description) -> {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Butler-FF/0.1").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException(description + " unavailable (HTTP " + response.statusCode() + "): " + uri);
            }
            return response.body();
        };
    }

    public enum FailureType { DOWNLOAD, VALIDATION }

    public record SeasonFailure(int season, FailureType type, String message) {}

    public record SeasonImportResult(int season, LocalDate asOfDate, boolean persisted, int providerRows,
                                     int providerRowsForSeason, int uniqueGsisPlayers,
                                     int zeroGamePlayers, int snapshotsWritten) {}

    public record HistoryImportResult(int startSeason, int endSeason, LocalDate asOfDate, boolean persisted,
                                      List<SeasonImportResult> successes, List<SeasonFailure> failures) {
        public HistoryImportResult {
            successes = List.copyOf(successes);
            failures = List.copyOf(failures);
        }
        public int seasonsRequested() { return endSeason - startSeason + 1; }
        public int seasonsSucceeded() { return successes.size(); }
        public int seasonsFailed() { return failures.size(); }
        public boolean complete() { return failures.isEmpty() && seasonsSucceeded() == seasonsRequested(); }
        public int playerSeasonsImported() { return successes.stream().mapToInt(SeasonImportResult::uniqueGsisPlayers).sum(); }
        public int snapshotsWritten() { return successes.stream().mapToInt(SeasonImportResult::snapshotsWritten).sum(); }
    }

    @FunctionalInterface
    interface Downloader {
        String download(URI uri, String description) throws IOException, InterruptedException;
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
