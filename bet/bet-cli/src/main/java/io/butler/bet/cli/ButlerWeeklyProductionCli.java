package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.NflversePlayerWeekProductionImporter;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/** CLI leaf for previewing or persisting raw nflverse regular-season week production. */
public final class ButlerWeeklyProductionCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerWeeklyProductionCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            NflversePlayerWeekProductionImporter importer = new NflversePlayerWeekProductionImporter(initializedDatabase());
            var result = options.persist() ? importer.refresh(options.season()) : importer.preview(options.season());
            print(result);
        } catch (IOException e) {
            System.err.println("nflverse weekly production download error: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("nflverse weekly production download interrupted");
            System.exit(1);
        } catch (SQLException e) {
            System.err.println("Database error while processing weekly production: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 3) {
            throw new IllegalArgumentException(
                "Usage: butler nflverse weekly-production-preview|weekly-production-refresh <season>");
        }
        int season;
        try {
            season = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("season must be a year between 1999 and 2100: " + args[2]);
        }
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be a year between 1999 and 2100: " + args[2]);
        }
        return new Options(season, "weekly-production-refresh".equalsIgnoreCase(args[1]));
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "nflverse".equalsIgnoreCase(args[0])
            && ("weekly-production-preview".equalsIgnoreCase(args[1])
                || "weekly-production-refresh".equalsIgnoreCase(args[1]));
    }

    static void print(NflversePlayerWeekProductionImporter.ImportResult result) {
        if (result == null) throw new IllegalArgumentException("result must not be null");
        System.out.println(result.persisted()
            ? "nflverse weekly production refresh"
            : "nflverse weekly production preview");
        System.out.printf("Season: %d  source=%s  as-of=%s%n",
            result.season(), NflversePlayerWeekProductionImporter.SOURCE, result.asOfDate());
        System.out.printf("Provider rows: total=%d season=%d regular-season=%d%n",
            result.providerRows(), result.requestedSeasonRows(), result.regularSeasonRows());
        System.out.printf("Matched player-weeks: %d  unmatched provider rows: %d  snapshots written: %d%n",
            result.matchedPlayerWeeks(), result.unmatchedProviderRows(), result.snapshotsWritten());
        System.out.println("Raw REG week production only; no fantasy scoring, lineup optimization, ranking, or recommendation is performed.");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    record Options(int season, boolean persist) {}
}
