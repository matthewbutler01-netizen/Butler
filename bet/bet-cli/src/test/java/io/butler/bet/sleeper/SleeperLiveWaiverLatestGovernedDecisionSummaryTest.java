package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SleeperLiveWaiverLatestGovernedDecisionSummaryTest {

    @Test
    void actionableAndLatestEvidenceRendersCurrentExactPersistedPlayerIdentities() throws Exception {
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED),
            evidence(SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED));

        var report = service.summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.CURRENT_AND_ACTIONABLE,
            report.state());
        assertEquals("Jauan Jennings", report.addPlayer().displayName());
        assertEquals("7049", report.addPlayer().sleeperPlayerId());
        assertEquals("Isaiah Bond", report.dropPlayer().displayName());
        assertEquals("12503", report.dropPlayer().sleeperPlayerId());
        assertEquals(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED,
            report.bf629State());
        assertEquals(SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED,
            report.bf631State());
    }

    @Test
    void staleLiveRosterStateIsRetainedOnlyAsDoNotActTraceability() throws Exception {
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.ADD_NO_LONGER_AVAILABLE),
            evidence(SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED));

        var report = service.summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.STALE_DO_NOT_ACT,
            report.state());
        assertEquals("Jauan Jennings", report.addPlayer().displayName());
        assertEquals("Isaiah Bond", report.dropPlayer().displayName());
        assertEquals(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.ADD_NO_LONGER_AVAILABLE,
            report.bf629State());
    }

    @Test
    void liveActionableButMarketLineageSupersededIsDoNotAct() throws Exception {
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED),
            evidence(SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.MARKET_LINEAGE_SUPERSEDED));

        var report = service.summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.STALE_DO_NOT_ACT,
            report.state());
        assertEquals(SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.MARKET_LINEAGE_SUPERSEDED,
            report.bf631State());
    }

    @Test
    void liveActionableButWaiverLineageSupersededIsDoNotAct() throws Exception {
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED),
            evidence(SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.WAIVER_LINEAGE_SUPERSEDED));

        var report = service.summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.STALE_DO_NOT_ACT,
            report.state());
        assertEquals(SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.WAIVER_LINEAGE_SUPERSEDED,
            report.bf631State());
    }

    @Test
    void liveActionableButBothEvidenceLineagesSupersededIsDoNotAct() throws Exception {
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED),
            evidence(SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.MARKET_AND_WAIVER_LINEAGE_SUPERSEDED));

        var report = service.summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.STALE_DO_NOT_ACT,
            report.state());
    }

    @Test
    void emptyAuditHistoryIsAValidNoDecisionSummaryWhenBothGatesAgree() throws Exception {
        var actionability = new SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationActionabilityRevalidation.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            null, null, null, null, null, null, null, 0,
            SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.NO_AUDITED_DECISION);
        var evidence = noAuditEvidence();
        var service = service(actionability, evidence);

        var report = service.summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.NO_AUDITED_DECISION,
            report.state());
        assertNull(report.addPlayer());
        assertNull(report.dropPlayer());
        assertEquals(SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.NO_AUDITED_DECISION,
            report.bf631State());
    }

    @Test
    void auditedNoTransactionIsAValidNoActionSummaryWhenAuditIdentityReconciles() throws Exception {
        var actionability = new SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationActionabilityRevalidation.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "audit", "2026-09-08T09:53:19Z", "NO_GOVERNED_TRANSACTION",
            null, null, null, null, 0,
            SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.NO_TRANSACTION_TO_REVALIDATE);
        var service = service(actionability,
            evidence(SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED));

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
            ignored -> evidence(SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED),
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
        var service = service(mismatch,
            evidence(SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED));

        var error = assertThrows(IllegalStateException.class, () -> service.summarize(target()));

        assertEquals("BF-630 BLOCKED: BF-629 actionability does not reconcile to BF-623 target",
            error.getMessage());
    }

    @Test
    void mismatchedBf631IdentityFailsClosed() {
        var mismatch = new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.POLICY_ID,
            "otherLeague", "owner", "sleeperLeague", 6,
            "audit", "2026-09-08T09:53:19Z", "market", "waiver",
            "market", "2026-09-08T08:10:17Z", "waiver", "waiver", "2026-09-08T08:10:00Z",
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED);
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED),
            mismatch);

        var error = assertThrows(IllegalStateException.class, () -> service.summarize(target()));

        assertEquals("BF-632 BLOCKED: BF-631 evidence lineage does not reconcile to BF-623 target",
            error.getMessage());
    }

    @Test
    void mismatchedLatestAuditIdentityAcrossBf629AndBf631FailsClosed() {
        var mismatch = new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "different-audit", "2026-09-08T09:53:19Z", "market", "waiver",
            "market", "2026-09-08T08:10:17Z", "waiver", "waiver", "2026-09-08T08:10:00Z",
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED);
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED),
            mismatch);

        var error = assertThrows(IllegalStateException.class, () -> service.summarize(target()));

        assertEquals("BF-632 BLOCKED: BF-629/BF-631 latest audit identity does not reconcile",
            error.getMessage());
    }

    @Test
    void disagreementOnWhetherAuditExistsFailsClosed() {
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED),
            noAuditEvidence());

        var error = assertThrows(IllegalStateException.class, () -> service.summarize(target()));

        assertEquals("BF-632 BLOCKED: BF-629/BF-631 disagree on whether an audited decision exists",
            error.getMessage());
    }

    private static SleeperLiveWaiverLatestGovernedDecisionSummary service(
        SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport revalidation,
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport evidence) {
        return new SleeperLiveWaiverLatestGovernedDecisionSummary(
            ignored -> revalidation,
            ignored -> evidence,
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

    private static SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport evidence(
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState state) {
        String latestMarket = switch (state) {
            case MARKET_LINEAGE_SUPERSEDED, MARKET_AND_WAIVER_LINEAGE_SUPERSEDED -> "market-new";
            default -> "market";
        };
        String latestWaiver = switch (state) {
            case WAIVER_LINEAGE_SUPERSEDED, MARKET_AND_WAIVER_LINEAGE_SUPERSEDED -> "waiver-new";
            default -> "waiver";
        };
        return new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "audit", "2026-09-08T09:53:19Z", "market", "waiver",
            latestMarket, "2026-09-08T08:10:17Z", "waiver", latestWaiver, "2026-09-08T08:10:00Z",
            state);
    }

    private static SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport noAuditEvidence() {
        return new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            null, null, null, null, null, null, null, null, null,
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.NO_AUDITED_DECISION);
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
