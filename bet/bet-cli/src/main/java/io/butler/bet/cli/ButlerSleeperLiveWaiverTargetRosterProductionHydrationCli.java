package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverTargetRosterProductionHydration;

import java.nio.file.Path;

/** BF-612 operator surface for guarded missing-only target-roster 2025 production hydration. */
public final class ButlerSleeperLiveWaiverTargetRosterProductionHydrationCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperLiveWaiverTargetRosterProductionHydrationCli() {}

    public static void main(String[] args) {
        try {
            Parsed parsed = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperLiveWaiverTargetRosterProductionHydration(database, DATABASE_PATH)
                .hydrate(parsed.leagueId(), parsed.ownerId()));
        } catch (SleeperLiveWaiverTargetRosterProductionHydration.HydrationRollbackException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Rollback restored: " + e.restored());
            System.err.println("Backup: " + e.backupPath());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Parsed parse(String[] args) {
        if (args == null || args.length != 2
            || args[0] == null || args[0].isBlank()
            || args[1] == null || args[1].isBlank()) {
            throw new IllegalArgumentException(
                "Usage: sleeperLiveWaiverTargetRosterProductionHydration <butler-league-id> <sleeper-owner-id>");
        }
        return new Parsed(args[0].trim(), args[1].trim());
    }

    static void print(SleeperLiveWaiverTargetRosterProductionHydration.HydrationReport report) {
        System.out.println("Sleeper 2026 guarded target-roster production hydration");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Butler league: " + report.leagueId());
        System.out.println("Exact target Sleeper owner: " + report.sleeperOwnerId());
        System.out.println("BF-603 market snapshot: " + report.marketSnapshotId());
        System.out.println("Referenced BF-602 waiver snapshot: " + report.waiverSnapshotId());
        System.out.println("Sleeper league / roster id: " + report.sleeperLeagueId() + " / " + report.rosterId());
        System.out.println("Pre-write backup retained: " + report.backupPath());
        System.out.println("BF-611 pre PRESENT / MISSING: " + report.prePresent() + "/" + report.preMissing());
        System.out.println("Missing target identities sent to nflverse importer: " + report.missingTargetsHydrated());
        System.out.println("nflverse provider rows total / 2025: "
            + report.providerRows() + "/" + report.providerRowsForSeason());
        System.out.println("GSIS->Sleeper crosswalk entries: " + report.crosswalkEntries());
        System.out.println("Target provider rows exact-mapped: " + report.targetProviderRowsMapped());
        System.out.println("Target eligible / matched / unmatched / snapshots written: "
            + report.targetEligiblePlayers() + "/" + report.targetMatchedPlayers() + "/"
            + report.unmatched().size() + "/" + report.snapshotsWritten());
        System.out.println("Production as-of: " + report.productionAsOfDate());
        if (!report.unmatched().isEmpty()) {
            System.out.println("Exact target identities with no governed 2025 NFL production row:");
            for (var player : report.unmatched()) {
                System.out.println("  " + player.sleeperId() + " | " + player.playerName()
                    + " | butler_player=" + player.playerId());
            }
        }
        System.out.println("BF-611 post PRESENT / MISSING: " + report.postPresent() + "/" + report.postMissing());
        System.out.println("Target-roster production hydration state: " + report.state());
        System.out.println();
        System.out.println("Boundary: BF-612 hydrates only exact BF-611 target-roster identities that lacked governed 2025 NFL production. An exact unmatched identity remains an explicit evidence gap, not zero production or a negative player grade. BF-612 does not score roster needs, rank players, pair add/drop candidates, recommend transactions, provide FAAB guidance, or emit value/confidence/probability claims.");
    }

    record Parsed(String leagueId, String ownerId) {}
}
