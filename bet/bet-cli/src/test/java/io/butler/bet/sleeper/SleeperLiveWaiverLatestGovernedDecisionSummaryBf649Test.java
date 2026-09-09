package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SleeperLiveWaiverLatestGovernedDecisionSummaryBf649Test {

    @Test
    void exposesExactBf631AuditedSnapshotIdsWithoutAnotherRead() throws Exception {
        var evidence = evidence(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED);
        var service = new SleeperLiveWaiverLatestGovernedDecisionSummary(
            ignored -> actionability(),
            ignored -> evidence,
            ignored -> telemetry(evidence),
            sleeperId -> switch (sleeperId) {
                case "7049" -> new SleeperLiveWaiverLatestGovernedDecisionSummary.PlayerDisplay(
                    "7049", "Jauan Jennings", "WR", "MIN");
                case "12503" -> new SleeperLiveWaiverLatestGovernedDecisionSummary.PlayerDisplay(
                    "12503", "Isaiah Bond", "WR", "CLE");
                default -> null;
            });

        var report = service.summarize(target());

        assertEquals("market-audited", report.auditedMarketSnapshotId());
        assertEquals("waiver-audited", report.auditedWaiverSnapshotId());
        assertEquals(SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED,
            report.bf631State());
        assertEquals("7049", report.addPlayer().sleeperPlayerId());
    }

    @Test
    void noAuditedDecisionDoesNotInventSnapshotIds() throws Exception {
        var actionability = new SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationActionabilityRevalidation.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            null, null, null, null, null, null, null, 0,
            SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.NO_AUDITED_DECISION);
        var evidence = new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            null, null, null, null, null, null, null, null, null,
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.NO_AUDITED_DECISION);
        var telemetry = new SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryReport(
            SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "2026-09-08T17:30:00Z",
            null, null, null, null, null, null,
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.NO_AUDITED_DECISION,
            SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryState.NO_AUDITED_DECISION);
        var service = new SleeperLiveWaiverLatestGovernedDecisionSummary(
            ignored -> actionability,
            ignored -> evidence,
            ignored -> telemetry,
            sleeperId -> null);

        var report = service.summarize(target());

        assertNull(report.auditedMarketSnapshotId());
        assertNull(report.auditedWaiverSnapshotId());
    }

    private static SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport actionability() {
        return new SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationActionabilityRevalidation.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "audit", "2026-09-08T09:53:19Z", "RECOMMEND_ADD_DROP",
            "7049", "12503", null, 6, 12,
            SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED);
    }

    private static SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport evidence(
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState state) {
        return new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "audit", "2026-09-08T09:53:19Z", "market-audited", "waiver-audited",
            "market-audited", "2026-09-08T08:10:17Z", "waiver-audited", "waiver-audited", "2026-09-08T08:10:00Z",
            state);
    }

    private static SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryReport telemetry(
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport evidence) {
        return new SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryReport(
            SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "2026-09-08T17:30:00Z",
            "audit", 10L,
            "2026-09-08T08:10:17Z", 20L,
            "2026-09-08T08:10:00Z", 30L,
            evidence.state(),
            SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryState.EVIDENCE_AGE_REPORTED);
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
