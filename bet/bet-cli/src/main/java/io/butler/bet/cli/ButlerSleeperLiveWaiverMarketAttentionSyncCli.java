package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverMarketAttentionSync;

import java.nio.file.Path;

/** BF-603 operator surface for fresh Sleeper waiver market-attention evidence. */
public final class ButlerSleeperLiveWaiverMarketAttentionSyncCli {
    private ButlerSleeperLiveWaiverMarketAttentionSyncCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException("Usage: sleeperLiveWaiverMarketAttentionSync <butler-league-id>");
            }
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            print(new SleeperLiveWaiverMarketAttentionSync(database).sync(args[0].trim()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverMarketAttentionSync.SyncReport report) {
        System.out.println("Sleeper 2026 live waiver market-attention sync");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Market evidence snapshot: " + report.marketSnapshotId());
        System.out.println("Referenced BF-602 waiver snapshot: " + report.waiverSnapshotId());
        System.out.println("BF-602 observed at UTC: " + report.waiverSnapshotObservedAtUtc());
        System.out.println("BF-602 age minutes: " + report.waiverSnapshotAge().toMinutes());
        System.out.println("Butler league: " + report.leagueId());
        System.out.println("Sleeper league: " + report.sleeperLeagueId());
        System.out.println("Provider season/status/leg: " + report.providerSeason() + "/"
            + report.providerStatus() + "/" + report.providerLeg());
        System.out.println("Trending lookback hours / per-direction result limit: "
            + report.lookbackHours() + "/" + report.resultLimit());
        System.out.println("League-eligible candidates retained: " + report.candidateCount());
        System.out.println("Sleeper add/drop frame sizes: " + report.addFrameSize() + "/" + report.dropFrameSize());
        System.out.println("Candidate frame membership ADD_ONLY/DROP_ONLY/BOTH/NEITHER: "
            + report.addOnlyCount() + "/" + report.dropOnlyCount() + "/"
            + report.bothCount() + "/" + report.neitherCount());
        System.out.println("Observed at UTC: " + report.observedAtUtc());
        System.out.println("Descriptive highest add-attention examples:");
        report.topAddExamples().forEach(value -> System.out.println("  " + value));
        System.out.println("Descriptive highest drop-attention examples:");
        report.topDropExamples().forEach(value -> System.out.println("  " + value));
        System.out.println("Descriptive highest net-add-attention examples:");
        report.topNetExamples().forEach(value -> System.out.println("  " + value));
        System.out.println("Immutable market-attention snapshots retained for league: "
            + report.marketSnapshotCountForLeague());
        System.out.println("Market-attention state: PERSISTED_VERIFIED");
        System.out.println();
        System.out.println("Boundary: BF-603 records provider-observed add/drop market attention for every BF-602 league-eligible free agent. Trend counts are descriptive evidence only; they are not player values, waiver rankings, FAAB guidance, add/drop recommendations, manager evaluations, confidence/probability outputs, or historical threshold changes.");
    }
}
