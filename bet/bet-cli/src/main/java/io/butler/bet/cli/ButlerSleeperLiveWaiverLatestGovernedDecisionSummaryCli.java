package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverLatestGovernedDecisionSummary;
import io.butler.bet.sleeper.SleeperLiveWaiverPostTransactionRosterConvergence;
import io.butler.bet.sleeper.SleeperLiveWaiverRecommendationManualRefreshPlan;

import java.nio.file.Path;

/** BF-630/BF-632/BF-634/BF-635/BF-636/BF-637/BF-638/BF-639 compact read-only operator view of the latest governed waiver decision. */
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
            var summary = new SleeperLiveWaiverLatestGovernedDecisionSummary(database).summarize(target);
            var convergence = new SleeperLiveWaiverPostTransactionRosterConvergence().inspect(target, summary);
            print(summary, convergence);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport report) {
        print(report, null);
    }

    static void print(
        SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport report,
        SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceReport convergence) {
        System.out.println("BF-630/BF-632/BF-634/BF-635/BF-636/BF-637/BF-638/BF-639 - compact latest governed waiver decision");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Target: " + report.leagueName() + " | " + value(report.teamName())
            + " | roster " + report.rosterId());
        System.out.println("Audit: " + value(report.auditId()) + " | captured=" + value(report.capturedAtUtc()));
        System.out.println("Decision status: " + report.state());
        System.out.println("BF-629 live actionability: " + report.bf629State());
        System.out.println("BF-631 evidence lineage: " + report.bf631State());
        System.out.println("BF-633 age telemetry: " + report.bf633State());
        System.out.println("BF-635 refresh-warning threshold seconds: "
            + SleeperLiveWaiverLatestGovernedDecisionSummary.REFRESH_WARNING_THRESHOLD_SECONDS);
        System.out.println("Telemetry observed at UTC: " + value(report.telemetryObservedAtUtc()));
        if (report.auditAgeSeconds() != null) {
            System.out.println("Audit age seconds: " + report.auditAgeSeconds());
            System.out.println("Latest BF-603 observed / age seconds: "
                + value(report.latestMarketObservedAtUtc()) + " / " + value(report.latestMarketAgeSeconds()));
            System.out.println("Latest BF-602 observed / age seconds: "
                + value(report.latestWaiverObservedAtUtc()) + " / " + value(report.latestWaiverAgeSeconds()));
        }

        if (report.addPlayer() != null && report.dropPlayer() != null) {
            System.out.println("ADD:  " + player(report.addPlayer()));
            System.out.println("DROP: " + player(report.dropPlayer()));
        } else {
            System.out.println("Decision: none");
        }

        if (report.state()
            == SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.TRANSACTION_ALREADY_COMPLETE) {
            System.out.println("Operator guard: TRANSACTION_ALREADY_COMPLETE - BF-629 found the exact audited add/drop already COMPLETE in Sleeper transaction evidence. The governed move is closed; do not resubmit it even if another Sleeper surface is lagging.");
        } else if (report.state()
            == SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.TRANSACTION_PENDING_DO_NOT_DUPLICATE) {
            System.out.println("Operator guard: TRANSACTION_PENDING_DO_NOT_DUPLICATE - BF-629 found the exact audited add/drop already PENDING in Sleeper transaction evidence. Do not submit a duplicate while Sleeper is processing it.");
        } else if (report.state() == SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.STALE_DO_NOT_ACT) {
            System.out.println("Operator guard: STALE_DO_NOT_ACT - a hard BF-629/BF-631 safety gate failed; the audited move is retained only for traceability.");
        } else if (report.state()
            == SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.CURRENT_REFRESH_RECOMMENDED) {
            System.out.println("Refresh trigger: " + refreshTrigger(report));
            System.out.println("Operator guard: CURRENT_REFRESH_RECOMMENDED - BF-629 live actionability and BF-631 latest evidence lineage are verified, but persisted BF-603/BF-602 evidence exceeds the approved 6-hour warning threshold. Refresh is recommended before acting; this warning does not hard-block the transaction and no refresh is executed automatically.");
        } else if (report.state()
            == SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.CURRENT_AND_ACTIONABLE) {
            System.out.println("Operator guard: CURRENT_AND_ACTIONABLE - BF-629 live actionability and BF-631 latest evidence lineage are verified, and BF-603/BF-602 evidence ages are at or below the approved 6-hour warning threshold; this remains read-only status, not transaction execution.");
        }

        printConvergence(convergence);
        printRefreshPlan(SleeperLiveWaiverRecommendationManualRefreshPlan.plan(report));

        System.out.println();
        System.out.println("Boundary: BF-630/BF-632/BF-634/BF-635/BF-636/BF-637/BF-638/BF-639 presents the BF-623/BF-628 audited result after BF-629 transaction-aware live actionability and BF-631 evidence-lineage checks, with BF-633 raw age telemetry, the approved 6-hour warning-only freshness policy, BF-636 manual refresh instructions when needed, BF-638 explicit completed/pending transaction lifecycle states, and BF-639 read-only post-transaction roster convergence. BF-629/BF-637 may use exact pending/complete Sleeper transaction evidence only to block duplicate action; BF-638 only classifies that read-only result; BF-639 only checks whether the completed add/drop has reached the live roster surface. Butler never submits, cancels, or replaces a transaction here. BF-636 does not execute any refresh step. This command does not refresh evidence, rerank players, create a replacement recommendation, set FAAB, submit a Sleeper transaction, mutate the league, write audit history, or write waiver/market snapshots.");
    }

    private static void printConvergence(
        SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceReport convergence) {
        if (convergence == null
            || convergence.state() == SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceState.NOT_APPLICABLE) {
            return;
        }
        System.out.println("BF-639 post-transaction roster convergence: " + convergence.state());
        System.out.println("BF-639 add current roster / target roster: "
            + value(convergence.addCurrentRosterId()) + " / " + convergence.rosterId());
        System.out.println("BF-639 drop current roster / target roster: "
            + value(convergence.dropCurrentRosterId()) + " / " + convergence.rosterId());
        if (convergence.state()
            == SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceState.POST_TRANSACTION_ROSTER_CONVERGED) {
            System.out.println("Next-cycle gate: VERIFIED - the live Sleeper roster surface reflects the completed audited add/drop. A new governed evidence cycle may now begin only through its explicit refresh steps.");
        } else {
            System.out.println("Next-cycle gate: HOLD - Sleeper transaction evidence is complete but the live roster surface has not converged to the audited add/drop. Do not create a new BF-602 snapshot from this roster state.");
        }
    }

    private static void printRefreshPlan(SleeperLiveWaiverRecommendationManualRefreshPlan.PlanReport plan) {
        if (plan.state() != SleeperLiveWaiverRecommendationManualRefreshPlan.PlanState.MANUAL_REFRESH_PLAN_READY) {
            return;
        }
        System.out.println();
        System.out.println("BF-636 - governed MANUAL refresh plan");
        System.out.println("Plan policy: " + plan.policyId());
        System.out.println("Plan state: " + plan.state());
        System.out.println("Operator instruction: run these commands manually, in order, one at a time. BF-636 executes none of them.");
        for (var step : plan.steps()) {
            System.out.println("  " + step.order() + ". " + step.bf() + " | " + step.mode() + " | " + step.taskName());
            System.out.println("     " + step.command());
            System.out.println("     Purpose: " + step.purpose());
        }
    }

    private static String refreshTrigger(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport report) {
        long threshold = SleeperLiveWaiverLatestGovernedDecisionSummary.REFRESH_WARNING_THRESHOLD_SECONDS;
        boolean market = report.latestMarketAgeSeconds() != null && report.latestMarketAgeSeconds() > threshold;
        boolean waiver = report.latestWaiverAgeSeconds() != null && report.latestWaiverAgeSeconds() > threshold;
        if (market && waiver) return "BF-603 market and BF-602 waiver evidence exceed 21600 seconds";
        if (market) return "BF-603 market evidence exceeds 21600 seconds";
        if (waiver) return "BF-602 waiver evidence exceeds 21600 seconds";
        return "none";
    }

    private static String player(SleeperLiveWaiverLatestGovernedDecisionSummary.PlayerDisplay player) {
        return player.displayName() + " | " + player.position() + " | NFL=" + value(player.nflTeam())
            + " | Sleeper=" + player.sleeperPlayerId();
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
