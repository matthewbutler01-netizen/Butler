package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverRecommendationManualRefreshPlanTest {

    @Test
    void refreshRecommendedProducesExactNineStepGovernedPlan() {
        var report = SleeperLiveWaiverRecommendationManualRefreshPlan.plan(
            summary(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.CURRENT_REFRESH_RECOMMENDED));

        assertEquals(SleeperLiveWaiverRecommendationManualRefreshPlan.PlanState.MANUAL_REFRESH_PLAN_READY,
            report.state());
        assertEquals("a75ccbfa-18b4-4e02-9d21-ccb0356568cf", report.leagueId());
        assertEquals(9, report.steps().size());

        assertEquals(List.of(
            "sleeperLiveWaiverSnapshotSync",
            "sleeperLiveWaiverMarketAttentionSync",
            "sleeperLiveWaiverProductionHydration",
            "sleeperLiveWaiverAvailabilitySync",
            "sleeperLiveWaiverCurrentWeekStatSync",
            "sleeperLiveWaiverTargetRosterProductionHydration",
            "sleeperLiveWaiverFinalRecommendationBundle",
            "sleeperLiveWaiverRecommendationAuditCapture",
            "sleeperLiveWaiverLatestGovernedDecisionSummary"),
            report.steps().stream().map(SleeperLiveWaiverRecommendationManualRefreshPlan.PlanStep::taskName).toList());

        assertEquals(List.of(
            SleeperLiveWaiverRecommendationManualRefreshPlan.StepMode.BUTLER_WRITE,
            SleeperLiveWaiverRecommendationManualRefreshPlan.StepMode.BUTLER_WRITE,
            SleeperLiveWaiverRecommendationManualRefreshPlan.StepMode.BUTLER_WRITE,
            SleeperLiveWaiverRecommendationManualRefreshPlan.StepMode.BUTLER_WRITE,
            SleeperLiveWaiverRecommendationManualRefreshPlan.StepMode.BUTLER_WRITE,
            SleeperLiveWaiverRecommendationManualRefreshPlan.StepMode.BUTLER_WRITE,
            SleeperLiveWaiverRecommendationManualRefreshPlan.StepMode.READ_ONLY,
            SleeperLiveWaiverRecommendationManualRefreshPlan.StepMode.BUTLER_WRITE,
            SleeperLiveWaiverRecommendationManualRefreshPlan.StepMode.READ_ONLY),
            report.steps().stream().map(SleeperLiveWaiverRecommendationManualRefreshPlan.PlanStep::mode).toList());

        for (int i = 0; i < report.steps().size(); i++) {
            var step = report.steps().get(i);
            assertEquals(i + 1, step.order());
            assertTrue(step.command().startsWith(".\\gradlew.bat :bet:bet-cli:" + step.taskName()));
            assertTrue(step.command().endsWith("--args=\"a75ccbfa-18b4-4e02-9d21-ccb0356568cf\""));
        }
    }

    @Test
    void currentAndActionableProducesNoPlan() {
        assertNotRequired(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.CURRENT_AND_ACTIONABLE);
    }

    @Test
    void staleDoNotActProducesNoPlan() {
        assertNotRequired(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.STALE_DO_NOT_ACT);
    }

    @Test
    void noAuditedDecisionProducesNoPlan() {
        assertNotRequired(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.NO_AUDITED_DECISION);
    }

    @Test
    void noTransactionProducesNoPlan() {
        assertNotRequired(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.NO_TRANSACTION_TO_ACT_ON);
    }

    private static void assertNotRequired(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState state) {
        var report = SleeperLiveWaiverRecommendationManualRefreshPlan.plan(summary(state));
        assertEquals(SleeperLiveWaiverRecommendationManualRefreshPlan.PlanState.NOT_REQUIRED, report.state());
        assertTrue(report.steps().isEmpty());
    }

    private static SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport summary(
        SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState state) {
        return new SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport(
            SleeperLiveWaiverLatestGovernedDecisionSummary.POLICY_ID,
            "a75ccbfa-18b4-4e02-9d21-ccb0356568cf",
            "1051699472830525440",
            "1312110516008677376",
            "Hard(CORE)-Dynasty",
            6,
            "nuke the whales",
            "0dddbd80-868c-4493-9a5d-1660d64ccdf9",
            "2026-09-08T09:53:19.643012Z",
            "RECOMMEND_ADD_DROP",
            new SleeperLiveWaiverLatestGovernedDecisionSummary.PlayerDisplay(
                "7049", "Jauan Jennings", "WR", "MIN"),
            new SleeperLiveWaiverLatestGovernedDecisionSummary.PlayerDisplay(
                "12503", "Isaiah Bond", "WR", "CLE"),
            SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED,
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED,
            SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryState.EVIDENCE_AGE_REPORTED,
            "2026-09-08T17:51:38.506133200Z",
            28698L,
            "2026-09-08T08:10:17.457360Z",
            34881L,
            "2026-09-08T08:10:00.277645900Z",
            34898L,
            state);
    }
}
