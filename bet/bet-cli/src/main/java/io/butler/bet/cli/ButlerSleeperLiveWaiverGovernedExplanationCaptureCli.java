package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverGovernedExplanationCapture;

import java.nio.file.Path;

/** BF-653 explicit operator surface for immutable explanation companion capture. */
public final class ButlerSleeperLiveWaiverGovernedExplanationCaptureCli {
    private ButlerSleeperLiveWaiverGovernedExplanationCaptureCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 2
                || args[0] == null || args[0].isBlank()
                || args[1] == null || args[1].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: sleeperLiveWaiverGovernedExplanationCapture <butler-league-id> <bf627-audit-id>");
            }
            String leagueId = args[0].trim();
            String auditId = args[1].trim();
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            var target = ButlerPersonalizedTargetCliSupport.verify(database, leagueId);
            ButlerPersonalizedTargetCliSupport.printVerified(target);
            print(new SleeperLiveWaiverGovernedExplanationCapture(database).capture(target, auditId));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverGovernedExplanationCapture.CaptureReport report) {
        System.out.println("BF-653 - immutable governed explanation companion capture");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Capture state: " + report.captureState());
        System.out.println("BF-627 audit id: " + report.auditId());
        System.out.println("Explanation id: " + report.explanationId());
        System.out.println("Captured at UTC: " + report.capturedAtUtc());
        System.out.println("BF-603 market / BF-602 waiver snapshot: "
            + report.marketSnapshotId() + " / " + report.waiverSnapshotId());
        System.out.println("Audited add / drop Sleeper ids: "
            + value(report.addSleeperPlayerId()) + " / " + value(report.dropSleeperPlayerId()));
        System.out.println("Explanation type: " + report.explanationType());
        System.out.println("Why this move: " + report.explanationText());
        System.out.println("Evidence policy: " + value(report.evidencePolicyId()));
        System.out.println("Evidence trace: " + value(report.evidenceTrace()));
        System.out.println();
        System.out.println("Boundary: BF-653 writes only an append-only explanation companion for an existing immutable BF-627 audit after exact BF-623/BF-620/BF-625 reconciliation when applicable. It does not modify the BF-627 audit, refresh evidence, rerank candidates, select a different add/drop, set FAAB, run BF-641, or submit/cancel/replace a Sleeper transaction.");
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
