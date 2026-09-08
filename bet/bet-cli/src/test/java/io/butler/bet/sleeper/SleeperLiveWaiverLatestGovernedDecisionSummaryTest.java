package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SleeperLiveWaiverLatestGovernedDecisionSummaryTest {

    @Test
    void exactSixHourEvidenceAgeRemainsCurrentAndActionable() throws Exception {
        var evidence = evidence(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED);
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED),
            evidence,
            telemetry(evidence, "audit", 999999L, 21600L, 21600L));

        var report = service.summarize(target());

        assertEquals(21600L, SleeperLiveWaiverLatestGovernedDecisionSummary.REFRESH_WARNING_THRESHOLD_SECONDS);
        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.CURRENT_AND_ACTIONABLE,
            report.state());
        assertEquals("Jauan Jennings", report.addPlayer().displayName());
        assertEquals("7049", report.addPlayer().sleeperPlayerId());
        assertEquals("Isaiah Bond", report.dropPlayer().displayName());
        assertEquals("12503", report.dropPlayer().sleeperPlayerId());
        assertEquals(21600L, report.latestMarketAgeSeconds());
        assertEquals(21600L, report.latestWaiverAgeSeconds());
    }

    @Test
    void marketAgeOneSecondPastThresholdRecommendsRefreshWithoutHardBlocking() throws Exception {
        var evidence = evidence(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED);
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED),
            evidence,
            telemetry(evidence, "audit", 10L, 21601L, 100L));

        var report = service.summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.CURRENT_REFRESH_RECOMMENDED,
            report.state());
        assertEquals(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED,
            report.bf629State());
        assertEquals(SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED,
            report.bf631State());
        assertEquals(21601L, report.latestMarketAgeSeconds());
        assertEquals(100L, report.latestWaiverAgeSeconds());
    }

    @Test
    void waiverAgeOneSecondPastThresholdRecommendsRefreshWithoutHardBlocking() throws Exception {
        var evidence = evidence(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED);
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED),
            evidence,
            telemetry(evidence, "audit", 10L, 100L, 21601L));

        var report = service.summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.CURRENT_REFRESH_RECOMMENDED,
            report.state());
        assertEquals(100L, report.latestMarketAgeSeconds());
        assertEquals(21601L, report.latestWaiverAgeSeconds());
    }

    @Test
    void bothEvidenceAgesPastThresholdRecommendRefresh() throws Exception {
        var evidence = evidence(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED);
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED),
            evidence,
            telemetry(evidence, "audit", 999999L, 34262L, 34280L));

        var report = service.summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.CURRENT_REFRESH_RECOMMENDED,
            report.state());
        assertEquals("Jauan Jennings", report.addPlayer().displayName());
        assertEquals("Isaiah Bond", report.dropPlayer().displayName());
    }

    @Test
    void auditAgeAloneNeverTriggersRefreshWarning() throws Exception {
        var evidence = evidence(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED);
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED),
            evidence,
            telemetry(evidence, "audit", 999999L, 10L, 20L));

        var report = service.summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.CURRENT_AND_ACTIONABLE,
            report.state());
        assertEquals(999999L, report.auditAgeSeconds());
    }

    @Test
    void staleLiveRosterStateWinsOverYoungEvidence() throws Exception {
        var evidence = evidence(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED);
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.ADD_NO_LONGER_AVAILABLE),
            evidence,
            telemetry(evidence, "audit", 1L, 1L, 1L));

        var report = service.summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.STALE_DO_NOT_ACT,
            report.state());
        assertEquals(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.ADD_NO_LONGER_AVAILABLE,
            report.bf629State());
    }

    @Test
    void staleLiveRosterStateWinsOverOldEvidence() throws Exception {
        var evidence = evidence(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED);
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.DROP_NO_LONGER_ON_TARGET_ROSTER),
            evidence,
            telemetry(evidence, "audit", 50000L, 50000L, 50000L));

        var report = service.summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.STALE_DO_NOT_ACT,
            report.state());
    }

    @Test
    void supersededMarketLineageWinsOverYoungEvidence() throws Exception {
        var evidence = evidence(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.MARKET_LINEAGE_SUPERSEDED);
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED),
            evidence,
            telemetry(evidence, "audit", 1L, 1L, 1L));

        var report = service.summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.STALE_DO_NOT_ACT,
            report.state());
        assertEquals(SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.MARKET_LINEAGE_SUPERSEDED,
            report.bf631State());
    }

    @Test
    void supersededWaiverLineageWinsOverOldEvidence() throws Exception {
        var evidence = evidence(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.WAIVER_LINEAGE_SUPERSEDED);
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED),
            evidence,
            telemetry(evidence, "audit", 50000L, 50000L, 50000L));

        var report = service.summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.STALE_DO_NOT_ACT,
            report.state());
    }

    @Test
    void emptyAuditHistoryRemainsValidAndDoesNotInventAges() throws Exception {
        var actionability = new SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationActionabilityRevalidation.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            null, null, null, null, null, null, null, 0,
            SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.NO_AUDITED_DECISION);
        var evidence = noAuditEvidence();
        var service = service(actionability, evidence, noAuditTelemetry());

        var report = service.summarize(target());

        assertEquals(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.NO_AUDITED_DECISION,
            report.state());
        assertNull(report.addPlayer());
        assertNull(report.dropPlayer());
        assertNull(report.auditAgeSeconds());
        assertNull(report.latestMarketAgeSeconds());
        assertNull(report.latestWaiverAgeSeconds());
    }

    @Test
    void auditedNoTransactionRemainsNoActionEvenWhenEvidenceIsOld() throws Exception {
        var actionability = new SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationActionabilityRevalidation.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "audit", "2026-09-08T09:53:19Z", "NO_GOVERNED_TRANSACTION",
            null, null, null, null, 0,
            SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.NO_TRANSACTION_TO_REVALIDATE);
        var evidence = evidence(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED);
        var service = service(actionability, evidence, telemetry(evidence, "audit", 50000L, 50000L, 50000L));

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
        var evidence = evidence(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED);
        var service = new SleeperLiveWaiverLatestGovernedDecisionSummary(
            ignored -> revalidation,
            ignored -> evidence,
            ignored -> telemetry(evidence, "audit", 10L, 20L, 30L),
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
        var evidence = evidence(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED);
        var service = service(mismatch, evidence, telemetry(evidence, "audit", 10L, 20L, 30L));

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
            mismatch,
            telemetry(mismatch, "audit", 10L, 20L, 30L));

        var error = assertThrows(IllegalStateException.class, () -> service.summarize(target()));

        assertEquals("BF-632 BLOCKED: BF-631 evidence lineage does not reconcile to BF-623 target",
            error.getMessage());
    }

    @Test
    void mismatchedBf633TargetIdentityFailsClosed() {
        var evidence = evidence(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED);
        var mismatch = new SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryReport(
            SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.POLICY_ID,
            "otherLeague", "owner", "sleeperLeague", 6,
            "2026-09-08T17:30:00Z", "audit", 10L,
            "2026-09-08T08:10:17Z", 20L,
            "2026-09-08T08:10:00Z", 30L,
            evidence.state(),
            SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryState.EVIDENCE_AGE_REPORTED);
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED),
            evidence,
            mismatch);

        var error = assertThrows(IllegalStateException.class, () -> service.summarize(target()));

        assertEquals("BF-634 BLOCKED: BF-633 telemetry does not reconcile to BF-623 target",
            error.getMessage());
    }

    @Test
    void mismatchedBf633AuditIdentityFailsClosed() {
        var evidence = evidence(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED);
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED),
            evidence,
            telemetry(evidence, "different-audit", 10L, 20L, 30L));

        var error = assertThrows(IllegalStateException.class, () -> service.summarize(target()));

        assertEquals("BF-634 BLOCKED: BF-633 latest audit identity does not reconcile to BF-629/BF-631",
            error.getMessage());
    }

    @Test
    void mismatchedBf633LineageStateFailsClosed() {
        var evidence = evidence(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED);
        var telemetry = new SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryReport(
            SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "2026-09-08T17:30:00Z", "audit", 10L,
            "2026-09-08T08:10:17Z", 20L,
            "2026-09-08T08:10:00Z", 30L,
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.MARKET_LINEAGE_SUPERSEDED,
            SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryState.EVIDENCE_AGE_REPORTED);
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED),
            evidence,
            telemetry);

        var error = assertThrows(IllegalStateException.class, () -> service.summarize(target()));

        assertEquals("BF-634 BLOCKED: BF-633 telemetry does not preserve the BF-631 evidence-lineage state",
            error.getMessage());
    }

    @Test
    void disagreementOnWhetherAuditExistsFailsClosedBeforeTelemetry() {
        var service = service(
            actionability(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED),
            noAuditEvidence(),
            noAuditTelemetry());

        var error = assertThrows(IllegalStateException.class, () -> service.summarize(target()));

        assertEquals("BF-632 BLOCKED: BF-629/BF-631 disagree on whether an audited decision exists",
            error.getMessage());
    }

    private static SleeperLiveWaiverLatestGovernedDecisionSummary service(
        SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport revalidation,
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport evidence,
        SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryReport telemetry) {
        return new SleeperLiveWaiverLatestGovernedDecisionSummary(
            ignored -> revalidation,
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

    private static SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryReport telemetry(
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport evidence,
        String auditId,
        long auditAge,
        long marketAge,
        long waiverAge) {
        return new SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryReport(
            SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "2026-09-08T17:30:00Z",
            auditId,
            auditAge,
            "2026-09-08T08:10:17Z",
            marketAge,
            "2026-09-08T08:10:00Z",
            waiverAge,
            evidence.state(),
            SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryState.EVIDENCE_AGE_REPORTED);
    }

    private static SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryReport noAuditTelemetry() {
        return new SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryReport(
            SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "2026-09-08T17:30:00Z",
            null, null, null, null, null, null,
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.NO_AUDITED_DECISION,
            SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryState.NO_AUDITED_DECISION);
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
