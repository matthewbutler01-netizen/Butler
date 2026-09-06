package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperHistoricalLineupSeasonEvidenceImporter;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/** Dedicated developer/operator entry point for one-pass historical Sleeper season lineup hydration. */
public final class ButlerHistoricalLineupSeasonSyncCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerHistoricalLineupSeasonSyncCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperHistoricalLineupSeasonEvidenceImporter(database)
                .syncSeason(options.leagueId(), options.season()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Sleeper historical lineup season sync interrupted: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Sleeper historical lineup season sync failed: " + e.getMessage());
            System.exit(1);
        } catch (SQLException e) {
            System.err.println("Database error while syncing historical lineup season: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (args == null || args.length != 2) {
            throw usage();
        }
        String leagueId = requireText(args[0], "league-id");
        int season;
        try {
            season = Integer.parseInt(requireText(args[1], "season"));
        } catch (NumberFormatException e) {
            throw usage();
        }
        if (season < 1999 || season > 2100) throw usage();
        return new Options(leagueId, season);
    }

    static void print(SleeperHistoricalLineupSeasonEvidenceImporter.SeasonImportResult result) {
        System.out.println("Sleeper historical lineup season synchronized.");
        System.out.println("Butler league: " + result.butlerLeagueId());
        System.out.println("Resolved Sleeper league: " + result.sleeperLeagueId());
        System.out.println("Season: " + result.season() + "  history-hops=" + result.historyHops());
        System.out.println("Week scan universe: "
            + SleeperHistoricalLineupSeasonEvidenceImporter.FIRST_WEEK + "-"
            + SleeperHistoricalLineupSeasonEvidenceImporter.LAST_WEEK);
        System.out.println("Observed matchup weeks synchronized: " + result.weeksImported());
        System.out.println("Team-week roster snapshots: " + result.teamWeekSnapshots());
        System.out.println("New Butler player mappings: " + result.newPlayersCreated());
        System.out.println("Source: " + result.source() + "  as-of=" + result.asOfDate());
        System.out.println("Eligibility provenance: current dated Sleeper fantasy-position observation; "
            + "not reconstructed historical eligibility.");
        System.out.println("Boundary: provider-observed evidence hydration only; no scoring, lineup optimization, "
            + "ranking, or recommendation is performed.");
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
            "Usage: historicalLineupSeasonSync --args=\"<butler-league-id> <season>\"");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    record Options(String leagueId, int season) {}
}
