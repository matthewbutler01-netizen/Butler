package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.NflverseAgingModelPlayerImporter;
import io.butler.bet.intelligence.NflverseAgingModelProductionImporter;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/** CLI leaf for the provider-backed aging-model training universe. */
public final class ButlerAgingModelUniverseCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerAgingModelUniverseCli() {}

    public static void main(String[] args) {
        try {
            if (isPlayersCommand(args)) {
                if (args.length != 2) throw new IllegalArgumentException("aging-model player command takes no additional arguments");
                boolean persist = args[1].equalsIgnoreCase("aging-model-players-refresh");
                var importer = new NflverseAgingModelPlayerImporter(initializedDatabase());
                printPlayers(persist ? importer.refresh() : importer.preview());
                return;
            }
            if (isProductionCommand(args)) {
                if (args.length != 4) throw new IllegalArgumentException("aging-model production command requires start and end season");
                int start = parseSeason(args[2]);
                int end = parseSeason(args[3]);
                if (start > end) throw new IllegalArgumentException("start season must be <= end season");
                boolean persist = args[1].equalsIgnoreCase("aging-model-production-refresh");
                var importer = new NflverseAgingModelProductionImporter(initializedDatabase());
                printProduction(persist ? importer.refresh(start, end) : importer.preview(start, end));
                return;
            }
            throw new IllegalArgumentException("unsupported aging-model universe command");
        } catch (IOException e) {
            System.err.println("nflverse download error: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("nflverse download interrupted");
            System.exit(1);
        } catch (SQLException e) {
            System.err.println("Database error while processing aging-model universe: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        }
    }

    static boolean isPlayersCommand(String[] args) {
        return args != null && args.length >= 2 && equals(args[0], "nflverse")
            && (equals(args[1], "aging-model-players-preview") || equals(args[1], "aging-model-players-refresh"));
    }

    static boolean isProductionCommand(String[] args) {
        return args != null && args.length >= 2 && equals(args[0], "nflverse")
            && (equals(args[1], "aging-model-production-preview") || equals(args[1], "aging-model-production-refresh"));
    }

    static void printPlayers(NflverseAgingModelPlayerImporter.ImportResult result) {
        System.out.println(result.persisted() ? "nflverse aging-model players refresh" : "nflverse aging-model players preview");
        System.out.println("As-of: " + result.asOfDate());
        System.out.printf("Provider rows: %d  unique GSIS players: %d  exact birth dates: %d%n",
            result.providerRows(), result.uniqueGsisPlayers(), result.uniquePlayersWithBirthDate());
        System.out.println(result.persisted()
            ? "Profile snapshots written: " + result.snapshotsWritten()
            : "Profile snapshots written: 0 (preview only)");
    }

    static void printProduction(NflverseAgingModelProductionImporter.HistoryImportResult result) {
        System.out.println(result.persisted() ? "nflverse aging-model production refresh" : "nflverse aging-model production preview");
        System.out.printf("Seasons: %d-%d  complete=%s  succeeded=%d/%d%n",
            result.startSeason(), result.endSeason(), result.complete(), result.seasonsSucceeded(), result.seasonsRequested());
        System.out.println("As-of: " + result.asOfDate());
        System.out.println("Player-seasons processed: " + result.playerSeasonsImported());
        System.out.println(result.persisted()
            ? "Production snapshots written: " + result.snapshotsWritten()
            : "Production snapshots written: 0 (preview only)");
        for (var success : result.successes()) {
            System.out.printf("  %d: players=%d zero-game=%d written=%d%n",
                success.season(), success.uniqueGsisPlayers(), success.zeroGamePlayers(), success.snapshotsWritten());
        }
        for (var failure : result.failures()) {
            System.out.printf("  %d: FAILED %s - %s%n", failure.season(), failure.type(), failure.message());
        }
    }

    static void printUsage() {
        System.out.println("  butler nflverse aging-model-players-preview");
        System.out.println("  butler nflverse aging-model-players-refresh");
        System.out.println("  butler nflverse aging-model-production-preview <start-season> <end-season>");
        System.out.println("  butler nflverse aging-model-production-refresh <start-season> <end-season>");
    }

    private static int parseSeason(String value) {
        try {
            int season = Integer.parseInt(value);
            if (season < 1999 || season > 2100) throw new NumberFormatException();
            return season;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("season must be a year between 1999 and 2100: " + value);
        }
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    private static boolean equals(String actual, String expected) {
        return actual != null && actual.equalsIgnoreCase(expected);
    }
}
