package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverGovernedExplanationLookup;

import java.nio.file.Path;

/** BF-653 read-only operator surface for a persisted governed explanation companion. */
public final class ButlerSleeperLiveWaiverGovernedExplanationLookupCli {
    private ButlerSleeperLiveWaiverGovernedExplanationLookupCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 2
                || args[0] == null || args[0].isBlank()
                || args[1] == null || args[1].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: sleeperLiveWaiverGovernedExplanationLookup <butler-league-id> <bf627-audit-id>");
            }
            String leagueId = args[0].trim();
            String auditId = args[1].trim();
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            var target = ButlerPersonalizedTargetCliSupport.verify(database, leagueId);
            ButlerPersonalizedTargetCliSupport.printVerified(target);
            print(new SleeperLiveWaiverGovernedExplanationLookup(database).lookup(target, auditId));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverGovernedExplanationLookup.LookupReport report) {
        System.out.println("BF-653 - read-only governed explanation lookup");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Lookup state: " + report.state());
        System.out.println("BF-627 audit id: " + report.auditId());
        System.out.println("Explanation id: " + value(report.explanationId()));
        System.out.println("Captured at UTC: " + value(report.capturedAtUtc()));
        System.out.println("BF-603 market / BF-602 waiver snapshot: "
            + report.marketSnapshotId() + " / " + report.waiverSnapshotId());
        System.out.println("Audited add / drop Sleeper ids: "
            + value(report.addSleeperPlayerId()) + " / " + value(report.dropSleeperPlayerId()));
        System.out.println("Explanation type: " + value(report.explanationType()));
        System.out.println("Why this move: " + value(report.explanationText()));
        System.out.println("Evidence policy: " + value(report.evidencePolicyId()));
        System.out.println("Evidence trace: " + value(report.evidenceTrace()));
        System.out.println();
        System.out.println("Boundary: BF-653 lookup is read-only persisted-state projection. It does not invoke BF-618, BF-620, BF-625, capture or rewrite audit/explanation records, refresh evidence, rerank candidates, set FAAB, run BF-641, or submit/cancel/replace a Sleeper transaction.");
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
