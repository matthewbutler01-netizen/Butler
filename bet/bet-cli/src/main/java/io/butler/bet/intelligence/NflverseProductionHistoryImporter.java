package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Imports an explicit inclusive range of nflverse regular-season production while reusing the
 * single-season parser, identity reconciliation, and persistence rules. Per-season upstream/data
 * failures remain visible instead of being silently skipped.
 */
public final class NflverseProductionHistoryImporter {
    private final NflversePlayerSeasonProductionImporter singleSeason;
    private final Downloader downloader;
    private final Supplier<LocalDate> today;

    public NflverseProductionHistoryImporter(Database database) {
        this(database, httpDownloader(), LocalDate::now);
    }

    NflverseProductionHistoryImporter(Database database, Downloader downloader, Supplier<LocalDate> today) {
        Objects.requireNonNull(database, "database must not be null");
        this.singleSeason = new NflversePlayerSeasonProductionImporter(database);
        this.downloader = Objects.requireNonNull(downloader, "downloader must not be null");
        this.today = Objects.requireNonNull(today, "today must not be null");
    }

    public HistoryImportResult preview(int startSeason, int endSeason)
        throws IOException, InterruptedException, SQLException {
        return process(startSeason, endSeason, false);
    }

    public HistoryImportResult refresh(int startSeason, int endSeason)
        throws IOException, InterruptedException, SQLException {
        return process(startSeason, endSeason, true);
    }

    private HistoryImportResult process(int startSeason, int endSeason, boolean persist)
        throws IOException, InterruptedException, SQLException {
        requireRange(startSeason, endSeason);
        String idsCsv = downloader.download(
            NflversePlayerSeasonProductionImporter.PLAYER_IDS_URI, "fantasy player id crosswalk");
        LocalDate asOfDate = Objects.requireNonNull(today.get(), "today supplier returned null");

        List<NflversePlayerSeasonProductionImporter.ImportResult> successes = new ArrayList<>();
        List<SeasonFailure> failures = new ArrayList<>();
        for (int season = startSeason; season <= endSeason; season++) {
            try {
                String statsCsv = downloader.download(
                    NflversePlayerSeasonProductionImporter.statsUri(season),
                    "nflverse player stats for " + season);
                var result = persist
                    ? singleSeason.importCsv(season, statsCsv, idsCsv, asOfDate)
                    : singleSeason.previewCsv(season, statsCsv, idsCsv, asOfDate);
                successes.add(result);
            } catch (IOException e) {
                failures.add(new SeasonFailure(season, FailureType.DOWNLOAD, message(e)));
            } catch (IllegalArgumentException e) {
                failures.add(new SeasonFailure(season, FailureType.VALIDATION, message(e)));
            }
        }

        return new HistoryImportResult(startSeason, endSeason, asOfDate, persist,
            List.copyOf(successes), List.copyOf(failures));
    }

    private static void requireRange(int startSeason, int endSeason) {
        if (startSeason < 1999 || startSeason > 2100) {
            throw new IllegalArgumentException("start season must be between 1999 and 2100");
        }
        if (endSeason < 1999 || endSeason > 2100) {
            throw new IllegalArgumentException("end season must be between 1999 and 2100");
        }
        if (startSeason > endSeason) {
            throw new IllegalArgumentException("start season must be <= end season");
        }
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

    public record SeasonFailure(int season, FailureType type, String message) {
        public SeasonFailure {
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(message, "message must not be null");
        }
    }

    public record HistoryImportResult(int startSeason, int endSeason, LocalDate asOfDate, boolean persisted,
                                      List<NflversePlayerSeasonProductionImporter.ImportResult> successes,
                                      List<SeasonFailure> failures) {
        public HistoryImportResult {
            Objects.requireNonNull(asOfDate, "asOfDate must not be null");
            successes = List.copyOf(Objects.requireNonNull(successes, "successes must not be null"));
            failures = List.copyOf(Objects.requireNonNull(failures, "failures must not be null"));
        }
        public int seasonsRequested() { return endSeason - startSeason + 1; }
        public int seasonsSucceeded() { return successes.size(); }
        public int seasonsFailed() { return failures.size(); }
        public boolean complete() { return failures.isEmpty() && successes.size() == seasonsRequested(); }
        public int snapshotsWritten() {
            return successes.stream().mapToInt(NflversePlayerSeasonProductionImporter.ImportResult::snapshotsWritten).sum();
        }
        public int matchedPlayerSeasons() {
            return successes.stream().mapToInt(NflversePlayerSeasonProductionImporter.ImportResult::matchedPlayers).sum();
        }
    }

    @FunctionalInterface
    interface Downloader {
        String download(URI uri, String description) throws IOException, InterruptedException;
    }
}
