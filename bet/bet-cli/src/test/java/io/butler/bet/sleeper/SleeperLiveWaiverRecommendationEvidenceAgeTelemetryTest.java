package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverRecommendationEvidenceAgeTelemetryTest {
    private static final Instant NOW = Instant.parse("2026-09-08T14:44:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void exactCurrentLineageReportsRawAgesWithoutThreshold() throws Exception {
        var service = service(lineage(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED,
            "2026-09-08T09:53:19Z",
            "2026-09-08T08:10:17Z",
            "2026-09-08T08:10:00Z"));

        var report = service.inspect(target());

        assertEquals(SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryState.EVIDENCE_AGE_REPORTED,
            report.state());
        assertEquals(SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED,
            report.bf631State());
        assertEquals("2026-09-08T14:44:00Z", report.observedAtUtc());
        assertEquals(17_441L, report.auditAgeSeconds());
        assertEquals(23_623L, report.latestMarketAgeSeconds());
        assertEquals(23_640L, report.latestWaiverAgeSeconds());
    }

    @Test
    void supersededLineageStateIsPreservedRatherThanReinterpreted() throws Exception {
        var service = service(lineage(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.MARKET_LINEAGE_SUPERSEDED,
            "2026-09-08T09:53:19Z",
            "2026-09-08T13:00:00Z",
            "2026-09-08T08:10:00Z"));

        var report = service.inspect(target());

        assertEquals(SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.MARKET_LINEAGE_SUPERSEDED,
            report.bf631State());
        assertEquals(SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryState.EVIDENCE_AGE_REPORTED,
            report.state());
        assertEquals(6_240L, report.latestMarketAgeSeconds());
    }

    @Test
    void emptyAuditHistoryIsValidWithoutInventedAges() throws Exception {
        var empty = new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            null, null, null, null,
            null, null, null, null, null,
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.NO_AUDITED_DECISION);
        var service = service(empty);

        var report = service.inspect(target());

        assertEquals(SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryState.NO_AUDITED_DECISION,
            report.state());
        assertNull(report.auditId());
        assertNull(report.auditAgeSeconds());
        assertNull(report.latestMarketObservedAtUtc());
        assertNull(report.latestMarketAgeSeconds());
        assertNull(report.latestWaiverObservedAtUtc());
        assertNull(report.latestWaiverAgeSeconds());
    }

    @Test
    void futurePersistedTimestampFailsClosed() {
        var service = service(lineage(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED,
            "2026-09-08T09:53:19Z",
            "2026-09-08T14:45:00Z",
            "2026-09-08T08:10:00Z"));

        var error = assertThrows(IllegalStateException.class, () -> service.inspect(target()));

        assertEquals("BF-633 BLOCKED: latest BF-603 observedAtUtc is in the future relative to observation clock",
            error.getMessage());
    }

    @Test
    void malformedPersistedTimestampFailsClosed() {
        var service = service(lineage(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED,
            "not-an-instant",
            "2026-09-08T08:10:17Z",
            "2026-09-08T08:10:00Z"));

        var error = assertThrows(IllegalStateException.class, () -> service.inspect(target()));

        assertTrue(error.getMessage().startsWith("BF-633 BLOCKED: malformed audit capturedAtUtc:"));
    }

    @Test
    void mismatchedBf631IdentityFailsClosed() {
        var mismatch = new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.POLICY_ID,
            "otherLeague", "owner", "sleeperLeague", 6,
            "audit", "2026-09-08T09:53:19Z", "market-old", "waiver-old",
            "market-old", "2026-09-08T08:10:17Z", "waiver-old",
            "waiver-old", "2026-09-08T08:10:00Z",
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED);
        var service = service(mismatch);

        var error = assertThrows(IllegalStateException.class, () -> service.inspect(target()));

        assertEquals("BF-633 BLOCKED: BF-631 evidence lineage does not reconcile to BF-623 target",
            error.getMessage());
    }

    private static SleeperLiveWaiverRecommendationEvidenceAgeTelemetry service(
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport lineage) {
        return new SleeperLiveWaiverRecommendationEvidenceAgeTelemetry(ignored -> lineage, CLOCK);
    }

    private static SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport lineage(
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState state,
        String capturedAt,
        String marketObservedAt,
        String waiverObservedAt) {
        return new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport(
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.POLICY_ID,
            "league", "owner", "sleeperLeague", 6,
            "audit", capturedAt, "market-old", "waiver-old",
            "market-latest", marketObservedAt, "waiver-referenced",
            "waiver-latest", waiverObservedAt, state);
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
