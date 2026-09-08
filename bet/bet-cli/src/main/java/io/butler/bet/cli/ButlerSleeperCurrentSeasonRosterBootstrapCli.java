package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperCurrentSeasonRosterBootstrap;

import java.nio.file.Path;

/** BF-600 operator surface for guarded 2026 current-season roster/player bootstrap. */
public final class ButlerSleeperCurrentSeasonRosterBootstrapCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperCurrentSeasonRosterBootstrapCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            var report = new SleeperCurrentSeasonRosterBootstrap(database, DATABASE_PATH)
                .bootstrap(options.leagueId(), options.expectedSleeperLeagueId());
            print(report);
        } catch (SleeperCurrentSeasonRosterBootstrap.BootstrapRollbackException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Rollback restored: " + e.restored());
            System.err.println("Backup: " + e.backupPath());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (args == null || args.length != 2) {
            throw new IllegalArgumentException(
                "Usage: sleeperCurrentSeasonRosterBootstrap <butler-league-id> <expected-sleeper-league-id>");
        }
        return new Options(
            requireText(args[0], "butler-league-id"),
            requireText(args[1], "expected-sleeper-league-id"));
    }

    static void print(SleeperCurrentSeasonRosterBootstrap.BootstrapReport report) {
        System.out.println("Sleeper 2026 guarded current-season roster/player bootstrap");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Butler league: " + report.leagueId());
        System.out.println("Sleeper league: " + report.sleeperLeagueId());
        System.out.println("Pre-write backup retained: " + report.backupPath());
        System.out.println("BF-599 roster count/entries: "
            + report.preflightRosterCount() + "/" + report.preflightRosterEntries());
        System.out.println("BF-599 current player identities needing bootstrap: "
            + report.preflightUnmappedPlayerIds());
        System.out.println("Imported teams/players/roster entries/performance snapshots: "
            + report.teamsImported() + "/" + report.playersImported() + "/"
            + report.rosterEntriesImported() + "/" + report.performanceSnapshotsImported());
        System.out.println("Post-import exact mapped/unmapped current players: "
            + report.postExactMappedCurrentPlayers() + "/" + report.postUnmappedCurrentPlayers());
        System.out.println("Bootstrap state: " + report.state());
        System.out.println();
        System.out.println("Boundary: BF-600 bootstraps only the already-linked 2026 Sleeper league after BF-599 READY_TO_HYDRATE, retains a database backup, and requires BF-598 roster/lineup/trade readiness after import. It does not infer a waiver universe or make recommendations, manager evaluations, threshold, confidence, or historical-methodology changes.");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    record Options(String leagueId, String expectedSleeperLeagueId) {}
}
