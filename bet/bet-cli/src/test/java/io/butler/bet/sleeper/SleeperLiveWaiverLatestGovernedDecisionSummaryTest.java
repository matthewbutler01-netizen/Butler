package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SleeperLiveWaiverLatestGovernedDecisionSummaryTest {

    @Test
    void actionableAuditedMoveRendersExactPersistedPlayerIdentities() throws Exception {
        var service = service(actionability(
            SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED));

        var report = service.summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.CURRENT_AND_ACTIONABLE,
            report.state());
        assertEquals("Jauan Jennings", report.addPlayer().displayName());
        assertEquals("7049", report.addPlayer().sleeperPlayerId());
        assertEquals("Isaiah Bond", report.dropPlayer().displayName());
        assertEquals("12503", report.dropPlayer().sleeperPlayerId());
        assertEquals(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED,
            report.bf629State());
    }

    @Test
    void staleAuditedMoveIsRetainedOnlyAsDoNotActTraceability() throws Exception {
        var service = service(actionability(
            SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.ADD_NO_LONGER_AVAILABLE));

        var report = service.summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.STALE_DO_NOT_ACT,
            report.state());
        assertEquals("Jauan Jennings", report.addPlayer().displayName());
        assertEquals("Isaiah Bond", report.dropPlayer().displayName());
        assertEquals(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.ADD_NO_LONGER_AVAILABLE,
            report.bf629State());
    }

    @Test
    void emptyAuditHistoryIsAValidNoDecisionSummary() throws Exception {
        var service = service(new SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationActionabilityRevalidation.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            null, null, null, null, null, null, null, 0,
            SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.NO_AUDITED_DECISION));

        var report = service.summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.NO_AUDITED_DECISION,
            report.state());
        assertNull(report.addPlayer());
        assertNull(report.dropPlayer());
    }

    @Test
    void auditedNoTransactionIsAValidNoActionSummary() throws Exception {
        var service = service(new SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationActionabilityRevalidation.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "audit", "2026-09-08T09:53:19Z", "NO_GOVERNED_TRANSACTION",
            null, null, null, null, 0,
            SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.NO_TRANSACTION_TO_REVALIDATE));

        var report = service.summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.NO_TRANSACTION_TO_ACT_ON,
            report.state());
        assertNull(report.addPlayer());
        assertNull(report.dropPlayer());
    }

    @Test
    void missingExactPersistedPlayerIdentityFailsClosed() {
        var revalidation = actionability(
            SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED);
        var service = new SleeperLiveWaiverLatestGovernedDecisionSummary(
            ignored -> revalidation,
            sleeperId -> "12503".equals(sleeperId)
                ? new SleeperLiveWaiverLatestGovernedDecisionSummary.PlayerDisplay(
                    "12503", "Isaiah Bond", "WR", "CLE")
                : null);

        var error = assertThrows(IllegalStateException.class, () -> service.summarize(target()));

        assertEquals("BF-630 BLOCKED: exact persisted Butler player is missing for add Sleeper id 7049",
            error.getMessage());
    }

    @Test
    void mismatchedBf629IdentityFailsClosed() {
        var mismatch = new SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationActionabilityRevalidation.POLICY_ID,
            "otherLeague", "owner", "sleeperLeague", 6,
            "audit", "2026-09-08T09:53:19Z", "RECOMMEND_ADD_DROP",
            "7049", "12503", null, 6, 12,
            SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED);
        var service = service(mismatch);

        var error = assertThrows(IllegalStateException.class, () -> service.summarize(target()));

        assertEquals("BF-630 BLOCKED: BF-629 actionability does not reconcile to BF-623 target",
            error.getMessage());
    }

    private static SleeperLiveWaiverLatestGovernedDecisionSummary service(
        SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport revalidation) {
        return new SleeperLiveWaiverLatestGovernedDecisionSummary(
            ignored -> revalidation,
            sleeperId -> switch (sleeperId) {
                case "7049" -> new SleeperLiveWaiverLatestGovernedDecisionSummary.PlayerDisplay(
                    "7049", "Jauan Jennings", "WR", "MIN");
                case "12503" -> new SleeperLiveWaiverLatestGovernedDecisionSummary.PlayerDisplay(
                    "12503", "Isaiah Bond", "WR", "CLE");
                default -> null;
            });
    }

    private static SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport actionability(
        SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState state) {
        Integer addRosterId = state
            == SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED
            ? null : 2;
        return new SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationActionabilityRevalidation.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "audit", "2026-09-08T09:53:19Z", "RECOMMEND_ADD_DROP",
            "7049", "12503", addRosterId, 6, 12, state);
    }

    private static SleeperPersonalizedTargetService.VerifiedTarget target() {
        return new SleeperPersonalizedTargetService.VerifiedTarget(
            SleeperPersonalizedTargetService.BF623_POLICY_ID,
            "league",
            "username",
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
