package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.NflverseProductionHistoryImporter;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/** CLI leaf for nflverse historical production range preview/refresh. */
public final class ButlerProductionHistoryCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerProductionHistoryCli() {}

    public static void main(String[] args) {
        Options options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
            return;
        }

        try {
            print(run(options));
        } catch (IOException e) {
            System.err.println("nflverse history download error: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("nflverse history download interrupted");
            System.exit(1);
        } catch (SQLException e) {
            System.err.println("Database error while processing nflverse production history: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("nflverse")
            && (args[1].equalsIgnoreCase("production-history-preview")
                || args[1].equalsIgnoreCase("production-history-refresh"));
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 4) {
            throw new IllegalArgumentException(
                "nflverse production history requires <start-season> <end-season>");
        }
        int startSeason = parseSeason(args[2], "start-season");
        int endSeason = parseSeason(args[3], "end-season");
        if (startSeason > endSeason) {
            throw new IllegalArgumentException("start-season must be <= end-season");
        }
        boolean persist = args[1].equalsIgnoreCase("production-history-refresh");
        return new Options(startSeason, endSeason, persist);
    }

    private static NflverseProductionHistoryImporter.HistoryImportResult run(Options options)
        throws IOException, InterruptedException, SQLException {
        NflverseProductionHistoryImporter importer = new NflverseProductionHistoryImporter(initializedDatabase());
        return options.persist()
            ? importer.refresh(options.startSeason(), options.endSeason())
            : importer.preview(options.startSeason(), options.endSeason());
    }

    static void print(NflverseProductionHistoryImporter.HistoryImportResult result) {
        if (result == null) throw new IllegalArgumentException("result must not be null");
        System.out.println(result.persisted()
            ? "nflverse production history refresh"
            : "nflverse production history preview");
        System.out.printf("Seasons: %d-%d  as-of=%s%n",
            result.startSeason(), result.endSeason(), result.asOfDate());
        System.out.printf("Range: requested=%d succeeded=%d failed=%d complete=%s%n",
            result.seasonsRequested(), result.seasonsSucceeded(), result.seasonsFailed(), result.complete());
        System.out.printf("Matched player-seasons: %d  snapshots written: %d%n",
            result.matchedPlayerSeasons(), result.snapshotsWritten());

        for (var season : result.successes()) {
            System.out.printf("  %d  matched=%d/%d  unmatched=%d  writes=%d%n",
                season.season(), season.matchedPlayers(), season.eligiblePlayers(),
                season.unmatchedPlayers(), season.snapshotsWritten());
        }
        if (!result.failures().isEmpty()) {
            System.out.println("Season failures:");
            result.failures().forEach(failure -> System.out.printf(
                "  %d  %s  %s%n", failure.season(), failure.type(), failure.message()));
        }
    }

    static void printUsage() {
        System.out.println("  butler nflverse production-history-preview <start-season> <end-season>");
        System.out.println("  butler nflverse production-history-refresh <start-season> <end-season>");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    private static int parseSeason(String value, String field) {
        try {
            int season = Integer.parseInt(value);
            if (season < 1999 || season > 2100) throw new NumberFormatException();
            return season;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a year between 1999 and 2100: " + value);
        }
    }

    record Options(int startSeason, int endSeason, boolean persist) {}
}
