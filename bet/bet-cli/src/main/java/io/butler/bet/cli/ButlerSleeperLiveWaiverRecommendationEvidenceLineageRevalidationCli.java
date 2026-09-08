package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverRecommendationEvidenceLineageRevalidation;

import java.nio.file.Path;

/** BF-631 operator surface for read-only persisted evidence-lineage freshness revalidation. */
public final class ButlerSleeperLiveWaiverRecommendationEvidenceLineageRevalidationCli {
    private ButlerSleeperLiveWaiverRecommendationEvidenceLineageRevalidationCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: sleeperLiveWaiverRecommendationEvidenceLineageRevalidation <butler-league-id>; exact Sleeper user/league/roster must be bound by BF-622");
            }
            String leagueId = args[0].trim();
            Database database = new Database(Path.of("butler.db"));
            var target = ButlerPersonalizedTargetCliSupport.verify(database, leagueId);
            ButlerPersonalizedTargetCliSupport.printVerified(target);
            print(new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation(database).revalidate(target));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport report) {
        System.out.println("BF-631 - read-only recommendation evidence-lineage freshness revalidation");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Butler league / BF-623 verified owner: " + report.leagueId() + " / " + report.sleeperOwnerId());
        System.out.println("Sleeper league / target roster: " + report.sleeperLeagueId() + " / " + report.rosterId());
        System.out.println("Latest audit: " + value(report.auditId()) + " | captured=" + value(report.capturedAtUtc()));
        System.out.println("Audited BF-603 / BF-602: " + value(report.auditedMarketSnapshotId()) + " / " + value(report.auditedWaiverSnapshotId()));
        System.out.println("Latest BF-603 / BF-602: " + value(report.latestMarketSnapshotId()) + " / " + value(report.latestWaiverSnapshotId()));
        System.out.println("Latest BF-603 observed: " + value(report.latestMarketObservedAtUtc()));
        System.out.println("Latest BF-602 observed: " + value(report.latestWaiverObservedAtUtc()));
        System.out.println("Latest BF-603 references BF-602: " + value(report.latestMarketReferencedWaiverSnapshotId()));
        System.out.println("Evidence-lineage state: " + report.state());
        System.out.println();
        System.out.println("Boundary: BF-631 is read-only. It compares the latest BF-628 integrity-verified audit lineage with persisted BF-603/BF-602 evidence freshness. It does not rerun BF-620, rerank players, manufacture a replacement recommendation, set FAAB, submit a Sleeper transaction, mutate the league, write audit history, or write waiver/market snapshots.");
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
