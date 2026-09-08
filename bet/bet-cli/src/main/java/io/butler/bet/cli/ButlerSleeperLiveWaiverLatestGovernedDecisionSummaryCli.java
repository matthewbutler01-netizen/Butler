package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverLatestGovernedDecisionSummary;

import java.nio.file.Path;

/** BF-630/BF-632 compact read-only operator view of the latest governed waiver decision. */
public final class ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli {
    private ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: sleeperLiveWaiverLatestGovernedDecisionSummary <butler-league-id>; exact Sleeper user/league/roster must be bound by BF-622");
            }
            String leagueId = args[0].trim();
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            var target = ButlerPersonalizedTargetCliSupport.verify(database, leagueId);
            ButlerPersonalizedTargetCliSupport.printVerified(target);
            print(new SleeperLiveWaiverLatestGovernedDecisionSummary(database).summarize(target));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport report) {
        System.out.println("BF-630/BF-632 - compact latest governed waiver decision");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Target: " + report.leagueName() + " | " + value(report.teamName())
            + " | roster " + report.rosterId());
        System.out.println("Audit: " + value(report.auditId()) + " | captured=" + value(report.capturedAtUtc()));
        System.out.println("Decision status: " + report.state());
        System.out.println("BF-629 live actionability: " + report.bf629State());
        System.out.println("BF-631 evidence lineage: " + report.bf631State());

        if (report.addPlayer() != null && report.dropPlayer() != null) {
            System.out.println("ADD:  " + player(report.addPlayer()));
            System.out.println("DROP: " + player(report.dropPlayer()));
        } else {
            System.out.println("Decision: none");
        }

        if (report.state() == SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.STALE_DO_NOT_ACT) {
            System.out.println("Operator guard: STALE_DO_NOT_ACT - the audited move is retained only for traceability.");
        } else if (report.state()
            == SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.CURRENT_AND_ACTIONABLE) {
            System.out.println("Operator guard: CURRENT_AND_ACTIONABLE - BF-629 live roster actionability and BF-631 latest evidence lineage are both verified; this remains read-only status, not transaction execution.");
        }

        System.out.println();
        System.out.println("Boundary: BF-630/BF-632 only presents the BF-623/BF-628 audited result after BF-629 live roster actionability and BF-631 evidence-lineage freshness checks. It does not rerank players, create a replacement recommendation, set FAAB, submit a Sleeper transaction, mutate the league, write audit history, or write waiver/market snapshots.");
    }

    private static String player(SleeperLiveWaiverLatestGovernedDecisionSummary.PlayerDisplay player) {
        return player.displayName() + " | " + player.position() + " | NFL=" + value(player.nflTeam())
            + " | Sleeper=" + player.sleeperPlayerId();
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
