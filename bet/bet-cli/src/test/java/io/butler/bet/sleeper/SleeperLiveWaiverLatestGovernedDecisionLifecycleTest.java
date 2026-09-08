package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SleeperLiveWaiverLatestGovernedDecisionLifecycleTest {

    @Test
    void completedExactTransactionGetsExplicitClosedLifecycleStateAndNoRefreshPlan() throws Exception {
        var report = service(
            SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.AUDITED_TRANSACTION_COMPLETE,
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED)
            .summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.TRANSACTION_ALREADY_COMPLETE,
            report.state());
        assertEquals(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.AUDITED_TRANSACTION_COMPLETE,
            report.bf629State());
        assertEquals("7049", report.addPlayer().sleeperPlayerId());
        assertEquals("12503", report.dropPlayer().sleeperPlayerId());
        assertEquals(SleeperLiveWaiverRecommendationManualRefreshPlan.PlanState.NOT_REQUIRED,
            SleeperLiveWaiverRecommendationManualRefreshPlan.plan(report).state());
    }

    @Test
    void pendingExactTransactionGetsExplicitNoDuplicateLifecycleStateAndNoRefreshPlan() throws Exception {
        var report = service(
            SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.AUDITED_TRANSACTION_PENDING,
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED)
            .summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.TRANSACTION_PENDING_DO_NOT_DUPLICATE,
            report.state());
        assertEquals(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.AUDITED_TRANSACTION_PENDING,
            report.bf629State());
        assertEquals(SleeperLiveWaiverRecommendationManualRefreshPlan.PlanState.NOT_REQUIRED,
            SleeperLiveWaiverRecommendationManualRefreshPlan.plan(report).state());
    }

    @Test
    void supersededEvidenceLineageStillWinsOverCompletedTransactionLifecyclePresentation() throws Exception {
        var report = service(
            SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.AUDITED_TRANSACTION_COMPLETE,
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.MARKET_LINEAGE_SUPERSEDED)
            .summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.STALE_DO_NOT_ACT,
            report.state());
        assertEquals(SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.MARKET_LINEAGE_SUPERSEDED,
            report.bf631State());
        assertEquals(SleeperLiveWaiverRecommendationManualRefreshPlan.PlanState.NOT_REQUIRED,
            SleeperLiveWaiverRecommendationManualRefreshPlan.plan(report).state());
    }

    private static SleeperLiveWaiverLatestGovernedDecisionSummary service(
        SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState actionabilityState,
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState evidenceState) {
        var actionability = new SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationActionabilityRevalidation.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "audit", "2026-09-08T18:11:28Z", "RECOMMEND_ADD_DROP",
            "7049", "12503", null, 6, 12, actionabilityState);
        var evidence = evidence(evidenceState);
        var telemetry = new SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryReport(
            SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "2026-09-08T20:43:35Z", "audit", 9127L,
            "2026-09-08T18:04:21Z", 9553L,
            "2026-09-08T18:02:57Z", 9637L,
            evidenceState,
            SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryState.EVIDENCE_AGE_REPORTED);
        return new SleeperLiveWaiverLatestGovernedDecisionSummary(
            ignored -> actionability,
            ignored -> evidence,
            ignored -> telemetry,
            sleeperId -> switch (sleeperId) {
                case "7049" -> new SleeperLiveWaiverLatestGovernedDecisionSummary.PlayerDisplay(
                    "7049", "Jauan Jennings", "WR", "MIN");
                case "12503" -> new SleeperLiveWaiverLatestGovernedDecisionSummary.PlayerDisplay(
                    "12503", "Isaiah Bond", "WR", "CLE");
                default -> null;
            });
    }

    private static SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport evidence(
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState state) {
        String latestMarket = state
            == SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.MARKET_LINEAGE_SUPERSEDED
            ? "new-market" : "market";
        return new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "audit", "2026-09-08T18:11:28Z", "market", "waiver",
            latestMarket, "2026-09-08T18:04:21Z", "waiver",
            "waiver", "2026-09-08T18:02:57Z", state);
    }

    private static SleeperPersonalizedTargetService.VerifiedTarget target() {
        return new SleeperPersonalizedTargetService.VerifiedTarget(
            SleeperPersonalizedTargetService.BF623_POLICY_ID,
            "league",
            "mbutler0624",
            "owner",
            "sleeperLeague",
            "Hard(CORE)-Dynasty",
            "in_season",
            6,
            SleeperPersonalizedTargetService.MembershipRole.OWNER,
            "mbutler0624",
            "nuke the whales",
            SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED);
    }
}
