package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverPostTransactionNextDecisionPlanTest {

    @Test
    void completedAndConvergedProducesExactlyNineStepManualNextDecisionPlan() {
        var plan = SleeperLiveWaiverPostTransactionNextDecisionPlan.plan(
            summary(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.TRANSACTION_ALREADY_COMPLETE),
            convergence(SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceState.POST_TRANSACTION_ROSTER_CONVERGED));

        assertEquals(SleeperLiveWaiverPostTransactionNextDecisionPlan.PlanState.NEXT_DECISION_PLAN_READY,
            plan.state());
        assertEquals(9, plan.steps().size());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9),
            plan.steps().stream().map(SleeperLiveWaiverPostTransactionNextDecisionPlan.PlanStep::order).toList());
        assertEquals("BF-602", plan.steps().get(0).bf());
        assertEquals("sleeperLiveWaiverSnapshotSync", plan.steps().get(0).taskName());
        assertEquals(SleeperLiveWaiverPostTransactionNextDecisionPlan.StepMode.BUTLER_WRITE,
            plan.steps().get(0).mode());
        assertTrue(plan.steps().get(0).command().contains("--args=\"league\""));
        assertEquals("BF-629/BF-631/BF-633/BF-635/BF-638/BF-639", plan.steps().get(8).bf());
        assertEquals("sleeperLiveWaiverLatestGovernedDecisionSummary", plan.steps().get(8).taskName());
        assertEquals(SleeperLiveWaiverPostTransactionNextDecisionPlan.StepMode.READ_ONLY,
            plan.steps().get(8).mode());
    }

    @Test
    void completedButPropagationPendingDoesNotExposeNextDecisionPlan() {
        var plan = SleeperLiveWaiverPostTransactionNextDecisionPlan.plan(
            summary(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.TRANSACTION_ALREADY_COMPLETE),
            convergence(SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceState.POST_TRANSACTION_ROSTER_PROPAGATION_PENDING));

        assertEquals(SleeperLiveWaiverPostTransactionNextDecisionPlan.PlanState.NOT_REQUIRED, plan.state());
        assertTrue(plan.steps().isEmpty());
    }

    @Test
    void pendingTransactionDoesNotExposeNextDecisionPlan() {
        var summary = summary(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.TRANSACTION_PENDING_DO_NOT_DUPLICATE);
        var convergence = new SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceReport(
            SleeperLiveWaiverPostTransactionRosterConvergence.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "audit", "7049", "12503", null, null, 0,
            SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceState.NOT_APPLICABLE);

        var plan = SleeperLiveWaiverPostTransactionNextDecisionPlan.plan(summary, convergence);

        assertEquals(SleeperLiveWaiverPostTransactionNextDecisionPlan.PlanState.NOT_REQUIRED, plan.state());
        assertTrue(plan.steps().isEmpty());
    }

    @Test
    void mismatchedConvergenceIdentityFailsClosed() {
        var mismatch = new SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceReport(
            SleeperLiveWaiverPostTransactionRosterConvergence.POLICY_ID,
            "other-league", "owner", "sleeperLeague", 6,
            "audit", "7049", "12503", 6, null, 12,
            SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceState.POST_TRANSACTION_ROSTER_CONVERGED);

        var error = assertThrows(IllegalStateException.class, () ->
            SleeperLiveWaiverPostTransactionNextDecisionPlan.plan(
                summary(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.TRANSACTION_ALREADY_COMPLETE),
                mismatch));

        assertEquals("BF-640 BLOCKED: BF-639 convergence does not reconcile to compact summary identity/audit",
            error.getMessage());
    }

    @Test
    void convergedReportWithoutCompletedLifecycleFailsClosed() {
        var error = assertThrows(IllegalStateException.class, () ->
            SleeperLiveWaiverPostTransactionNextDecisionPlan.plan(
                summary(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.CURRENT_AND_ACTIONABLE),
                convergence(SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceState.POST_TRANSACTION_ROSTER_CONVERGED)));

        assertEquals("BF-640 BLOCKED: BF-639 reports post-transaction convergence without a completed compact transaction lifecycle",
            error.getMessage());
    }

    @Test
    void completedConvergenceWithDifferentAddDropIdentityFailsClosed() {
        var mismatch = new SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceReport(
            SleeperLiveWaiverPostTransactionRosterConvergence.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "audit", "999", "12503", 6, null, 12,
            SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceState.POST_TRANSACTION_ROSTER_CONVERGED);

        var error = assertThrows(IllegalStateException.class, () ->
            SleeperLiveWaiverPostTransactionNextDecisionPlan.plan(
                summary(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.TRANSACTION_ALREADY_COMPLETE),
                mismatch));

        assertEquals("BF-640 BLOCKED: BF-639 convergence add/drop identity does not match completed compact transaction",
            error.getMessage());
    }

    private static SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport summary(
        SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState state) {
        var bf629 = state == SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.TRANSACTION_ALREADY_COMPLETE
            ? SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.AUDITED_TRANSACTION_COMPLETE
            : state == SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.TRANSACTION_PENDING_DO_NOT_DUPLICATE
                ? SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.AUDITED_TRANSACTION_PENDING
                : SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED;
        return new SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport(
            SleeperLiveWaiverLatestGovernedDecisionSummary.POLICY_ID,
            "league", "owner", "sleeperLeague", "Hard(CORE)-Dynasty", 6, "nuke the whales",
            "audit", "2026-09-08T18:11:28Z", "RECOMMEND_ADD_DROP",
            "market-audited", "waiver-audited",
            new SleeperLiveWaiverLatestGovernedDecisionSummary.PlayerDisplay("7049", "Jauan Jennings", "WR", "MIN"),
            new SleeperLiveWaiverLatestGovernedDecisionSummary.PlayerDisplay("12503", "Isaiah Bond", "WR", "CLE"),
            bf629,
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED,
            SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryState.EVIDENCE_AGE_REPORTED,
            "2026-09-08T21:18:37Z", 11229L,
            "2026-09-08T18:04:21Z", 11655L,
            "2026-09-08T18:02:57Z", 11739L,
            state);
    }

    private static SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceReport convergence(
        SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceState state) {
        return new SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceReport(
            SleeperLiveWaiverPostTransactionRosterConvergence.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "audit", "7049", "12503",
            state == SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceState.POST_TRANSACTION_ROSTER_CONVERGED ? 6 : null,
            state == SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceState.POST_TRANSACTION_ROSTER_PROPAGATION_PENDING ? 6 : null,
            12, state);
    }
}
