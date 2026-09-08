package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverSnapshotSync;

import java.nio.file.Path;

/** BF-602 operator surface for governed live waiver snapshot persistence. */
public final class ButlerSleeperLiveWaiverSnapshotSyncCli {
    private ButlerSleeperLiveWaiverSnapshotSyncCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException("Usage: sleeperLiveWaiverSnapshotSync <butler-league-id>");
            }
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            print(new SleeperLiveWaiverSnapshotSync(database).sync(args[0].trim()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverSnapshotSync.SyncReport report) {
        System.out.println("Sleeper 2026 governed live waiver snapshot sync");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Eligibility policy: " + report.eligibilityPolicyId());
        System.out.println("Snapshot: " + report.snapshotId());
        System.out.println("Butler league: " + report.leagueId());
        System.out.println("Sleeper league: " + report.sleeperLeagueId());
        System.out.println("Observed at UTC: " + report.observedAtUtc());
        System.out.println("Persisted lineup slots: " + report.lineupSlots());
        System.out.println("Derived league-eligible fantasy positions: " + report.eligiblePositions());
        System.out.println("Persisted active-source entries: " + report.persistedActiveEntries());
        System.out.println("Current roster identities / active-source rostered entries: "
            + report.currentRosterIdentities() + "/" + report.activeRosteredEntries());
        System.out.println("Rostered identities absent from active source: " + report.rosteredAbsentActive());
        System.out.println("Persisted free-agent entries: " + report.freeAgentEntries());
        System.out.println("League-eligible free-agent entries: " + report.leagueEligibleFreeAgents());
        System.out.println("Eligibility reason counts: " + report.eligibilityReasons());
        System.out.println("Canonical Butler players before/after: "
            + report.canonicalPlayersBefore() + "/" + report.canonicalPlayersAfter());
        System.out.println("Immutable waiver snapshots retained for league: " + report.snapshotCountForLeague());
        System.out.println("League-eligible examples:");
        report.eligibleExamples().forEach(value -> System.out.println("  " + value));
        System.out.println("Snapshot state: PERSISTED_VERIFIED");
        System.out.println();
        System.out.println("Boundary: BF-602 persists the complete BF-601-proven active-source identity frame and a deterministic league-eligibility projection. It does not rank waiver targets, assign FAAB, recommend adds/drops, evaluate managers, tune confidence/thresholds, or change historical methodology.");
    }
}
