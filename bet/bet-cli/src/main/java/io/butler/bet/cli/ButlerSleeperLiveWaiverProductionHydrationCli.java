package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverProductionHydration;

import java.nio.file.Path;

/** BF-605 operator surface for guarded market-active canonical/production hydration. */
public final class ButlerSleeperLiveWaiverProductionHydrationCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperLiveWaiverProductionHydrationCli() {}

    public static void main(String[] args) {
        try {
            String leagueId = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperLiveWaiverProductionHydration(database, DATABASE_PATH).hydrate(leagueId));
        } catch (SleeperLiveWaiverProductionHydration.HydrationRollbackException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Rollback restored: " + e.restored());
            System.err.println("Backup: " + e.backupPath());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static String parse(String[] args) {
        if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
            throw new IllegalArgumentException(
                "Usage: sleeperLiveWaiverProductionHydration <butler-league-id>");
        }
        return args[0].trim();
    }

    static void print(SleeperLiveWaiverProductionHydration.HydrationReport report) {
        System.out.println("Sleeper 2026 guarded market-active production hydration");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Butler league: " + report.leagueId());
        System.out.println("Referenced BF-603 market snapshot: " + report.marketSnapshotId());
        System.out.println("Pre-write backup retained: " + report.backupPath());
        System.out.println("Market-active target candidates: " + report.targetCandidates());
        System.out.println("Canonical Butler players before / created / after: "
            + report.canonicalPlayersBefore() + " / " + report.canonicalPlayersCreated()
            + " / " + report.canonicalPlayersAfter());
        System.out.println("BF-604 pre UNMAPPED / MAPPED_NO_2025_PRODUCTION / MAPPED_WITH_2025_PRODUCTION: "
            + report.preUnmapped() + "/" + report.preMappedNoProduction() + "/" + report.preMappedWithProduction());
        System.out.println("nflverse provider rows total / 2025: "
            + report.providerRows() + "/" + report.providerRowsForSeason());
        System.out.println("GSIS->Sleeper crosswalk entries: " + report.crosswalkEntries());
        System.out.println("Target provider rows exact-mapped: " + report.targetProviderRowsMapped());
        System.out.println("Target canonical eligible / matched / unmatched / snapshots written: "
            + report.targetEligiblePlayers() + "/" + report.targetMatchedPlayers() + "/"
            + report.targetUnmatchedPlayers() + "/" + report.snapshotsWritten());
        System.out.println("Production as-of: " + report.productionAsOfDate());
        if (!report.unmatched().isEmpty()) {
            System.out.println("Exact target identities with no 2025 nflverse production row:");
            report.unmatched().stream().limit(20).forEach(player ->
                System.out.println("  " + player.sleeperId() + " | " + player.playerName()
                    + " | butler_player=" + player.playerId()));
            if (report.unmatched().size() > 20) {
                System.out.println("  ... " + (report.unmatched().size() - 20) + " more");
            }
        }
        System.out.println("BF-604 post UNMAPPED / MAPPED_NO_2025_PRODUCTION / MAPPED_WITH_2025_PRODUCTION: "
            + report.postUnmapped() + "/" + report.postMappedNoProduction() + "/" + report.postMappedWithProduction());
        System.out.println("Hydration state: " + report.state());
        System.out.println();
        System.out.println("Boundary: BF-605 bootstraps only exact BF-603 market-active Sleeper identities and hydrates only exact-crosswalk 2025 nflverse production for that fixed frame. Missing prior-season production remains explicit. It does not rank waiver candidates, assign FAAB, recommend adds/drops, select winners, evaluate managers, or emit confidence/probability claims.");
    }
}
