package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SleeperLiveWaiverRecommendationActionabilityRevalidationTest {

    @Test
    void verifiedAuditedMoveIsLiveActionableWhenAddIsUnrosteredAndDropRemainsOnTargetRoster() throws Exception {
        var service = service(recommendationHistory(), List.of(
            roster(2, "other", "300"),
            roster(6, "owner", "12503", "900")));

        var report = service.revalidate(target());

        assertEquals(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED,
            report.state());
        assertNull(report.addCurrentRosterId());
        assertEquals(6, report.dropCurrentRosterId());
        assertEquals(2, report.currentRosterCount());
        assertEquals("7049", report.addSleeperPlayerId());
        assertEquals("12503", report.dropSleeperPlayerId());
    }

    @Test
    void addAlreadyRosteredMakesRecordedMoveStaleWithoutReplacementRecommendation() throws Exception {
        var service = service(recommendationHistory(), List.of(
            roster(2, "other", "7049"),
            roster(6, "owner", "12503")));

        var report = service.revalidate(target());

        assertEquals(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.ADD_NO_LONGER_AVAILABLE,
            report.state());
        assertEquals(2, report.addCurrentRosterId());
        assertEquals(6, report.dropCurrentRosterId());
    }

    @Test
    void dropGoneFromTargetRosterMakesRecordedMoveStale() throws Exception {
        var service = service(recommendationHistory(), List.of(
            roster(2, "other", "300"),
            roster(6, "owner", "900")));

        var report = service.revalidate(target());

        assertEquals(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.DROP_NO_LONGER_ON_TARGET_ROSTER,
            report.state());
        assertNull(report.addCurrentRosterId());
        assertNull(report.dropCurrentRosterId());
    }

    @Test
    void bothUnavailableAndDropGoneAreReportedTogether() throws Exception {
        var service = service(recommendationHistory(), List.of(
            roster(2, "other", "7049"),
            roster(6, "owner", "900")));

        var report = service.revalidate(target());

        assertEquals(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.ADD_AND_DROP_NO_LONGER_ACTIONABLE,
            report.state());
        assertEquals(2, report.addCurrentRosterId());
        assertNull(report.dropCurrentRosterId());
    }

    @Test
    void noTransactionAuditIsValidNonActionableStateWithoutRosterFetch() throws Exception {
        var service = new SleeperLiveWaiverRecommendationActionabilityRevalidation(
            ignored -> noTransactionHistory(),
            ignored -> { throw new AssertionError("roster source must not be called"); });

        var report = service.revalidate(target());

        assertEquals(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.NO_TRANSACTION_TO_REVALIDATE,
            report.state());
        assertEquals(0, report.currentRosterCount());
    }

    @Test
    void emptyHistoryIsValidAndDoesNotFetchRosters() throws Exception {
        var service = new SleeperLiveWaiverRecommendationActionabilityRevalidation(
            ignored -> emptyHistory(),
            ignored -> { throw new AssertionError("roster source must not be called"); });

        var report = service.revalidate(target());

        assertEquals(SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.NO_AUDITED_DECISION,
            report.state());
        assertNull(report.auditId());
    }

    @Test
    void duplicateLivePlayerOwnershipFailsClosed() {
        var service = service(recommendationHistory(), List.of(
            roster(2, "other", "7049"),
            roster(6, "owner", "12503", "7049")));

        var error = assertThrows(IllegalStateException.class, () -> service.revalidate(target()));
        assertEquals(true, error.getMessage().contains("appears on multiple rosters"));
    }

    @Test
    void currentTargetRosterOwnerMismatchFailsClosed() {
        var service = service(recommendationHistory(), List.of(
            roster(6, "different-owner", "12503")));

        var error = assertThrows(IllegalStateException.class, () -> service.revalidate(target()));
        assertEquals(true, error.getMessage().contains("owner no longer matches BF-623"));
    }

    private static SleeperLiveWaiverRecommendationActionabilityRevalidation service(
        SleeperLiveWaiverRecommendationAuditHistory.HistoryReport history,
        List<SleeperJsonParser.SleeperRoster> rosters) {
        return new SleeperLiveWaiverRecommendationActionabilityRevalidation(
            ignored -> history,
            ignored -> rosters);
    }

    private static SleeperPersonalizedTargetService.VerifiedTarget target() {
        return new SleeperPersonalizedTargetService.VerifiedTarget(
            SleeperPersonalizedTargetService.BF623_POLICY_ID,
            "league-butler",
            "mbutler0624",
            "owner",
            "league-sleeper",
            "Hard(CORE)-Dynasty",
            "in_season",
            6,
            SleeperPersonalizedTargetService.MembershipRole.OWNER,
            "mbutler0624",
            "nuke the whales",
            SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED);
    }

    private static SleeperLiveWaiverRecommendationAuditHistory.HistoryReport recommendationHistory() {
        return history(new SleeperLiveWaiverRecommendationAuditHistory.HistoryEntry(
            "audit-1", "2026-09-08T09:53:19Z", "league-butler", "owner", "league-sleeper", 6,
            2026, "in_season", 1, "market", "waiver", "UNIQUE_ADD_DROP_SELECTED", "RECOMMEND_ADD_DROP",
            "7049", "12503", SleeperLiveWaiverRecommendationAuditHistory.IntegrityState.LINEAGE_AND_IDENTITY_VERIFIED));
    }

    private static SleeperLiveWaiverRecommendationAuditHistory.HistoryReport noTransactionHistory() {
        return history(new SleeperLiveWaiverRecommendationAuditHistory.HistoryEntry(
            "audit-2", "2026-09-08T09:53:19Z", "league-butler", "owner", "league-sleeper", 6,
            2026, "in_season", 1, "market", "waiver", "NO_GOVERNED_TRANSACTION", "NO_GOVERNED_TRANSACTION",
            null, null, SleeperLiveWaiverRecommendationAuditHistory.IntegrityState.LINEAGE_AND_IDENTITY_VERIFIED));
    }

    private static SleeperLiveWaiverRecommendationAuditHistory.HistoryReport history(
        SleeperLiveWaiverRecommendationAuditHistory.HistoryEntry entry) {
        return new SleeperLiveWaiverRecommendationAuditHistory.HistoryReport(
            SleeperLiveWaiverRecommendationAuditHistory.POLICY_ID,
            "league-butler", "owner", "league-sleeper", 6, List.of(entry),
            SleeperLiveWaiverRecommendationAuditHistory.HistoryState.HISTORY_INTEGRITY_VERIFIED);
    }

    private static SleeperLiveWaiverRecommendationAuditHistory.HistoryReport emptyHistory() {
        return new SleeperLiveWaiverRecommendationAuditHistory.HistoryReport(
            SleeperLiveWaiverRecommendationAuditHistory.POLICY_ID,
            "league-butler", "owner", "league-sleeper", 6, List.of(),
            SleeperLiveWaiverRecommendationAuditHistory.HistoryState.EMPTY_HISTORY);
    }

    private static SleeperJsonParser.SleeperRoster roster(int rosterId, String ownerId, String... players) {
        return new SleeperJsonParser.SleeperRoster(
            rosterId, ownerId, List.of(players), List.of(), List.of(), List.of());
    }
}
