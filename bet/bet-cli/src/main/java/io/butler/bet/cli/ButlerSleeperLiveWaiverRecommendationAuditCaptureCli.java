package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverRecommendationAuditCapture;

import java.nio.file.Path;

/** BF-627 explicit operator surface for immutable governed recommendation audit capture. */
public final class ButlerSleeperLiveWaiverRecommendationAuditCaptureCli {
    private ButlerSleeperLiveWaiverRecommendationAuditCaptureCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: sleeperLiveWaiverRecommendationAuditCapture <butler-league-id>; exact Sleeper user/league/roster must be bound by BF-622");
            }
            String leagueId = args[0].trim();
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            var target = ButlerPersonalizedTargetCliSupport.verify(database, leagueId);
            ButlerPersonalizedTargetCliSupport.printVerified(target);
            print(new SleeperLiveWaiverRecommendationAuditCapture(database).capture(target));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverRecommendationAuditCapture.CaptureReport report) {
        System.out.println("BF-627 - immutable governed recommendation audit capture");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Capture state: " + report.captureState());
        System.out.println("Audit id: " + report.auditId());
        System.out.println("Captured at UTC: " + report.capturedAtUtc());
        System.out.println("Butler league / BF-623 verified owner: " + report.leagueId() + " / " + report.sleeperOwnerId());
        System.out.println("Sleeper league / target roster: " + report.sleeperLeagueId() + " / " + report.rosterId());
        System.out.println("Provider season/status/leg: " + report.providerSeason() + "/" + report.providerStatus()
            + "/" + value(report.providerLeg()));
        System.out.println("BF-603 market / BF-602 waiver snapshot: " + report.marketSnapshotId() + " / " + report.waiverSnapshotId());
        System.out.println("Selection / recommendation state: " + report.selectionState() + " / " + report.recommendationState());
        if (report.addSleeperPlayerId() != null) {
            System.out.println("Captured add / drop Sleeper ids: " + report.addSleeperPlayerId() + " / " + report.dropSleeperPlayerId());
        } else {
            System.out.println("Captured add / drop Sleeper ids: none / none");
        }
        System.out.println("Immutable audit records retained for league: " + report.retainedAuditRecordsForLeague());
        System.out.println();
        System.out.println("Boundary: BF-627 writes only an immutable Butler audit record after BF-623 live identity verification and BF-620 lineage reconciliation. It does not submit a Sleeper transaction, set FAAB, mutate the Sleeper league, or change the governed recommendation methodology.");
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
