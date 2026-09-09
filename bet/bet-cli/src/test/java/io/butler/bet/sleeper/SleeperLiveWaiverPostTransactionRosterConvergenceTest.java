package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SleeperLiveWaiverPostTransactionRosterConvergenceTest {

    @Test
    void completedTransactionIsConvergedWhenAddIsOnTargetAndDropIsGoneFromTarget() throws Exception {
        var service = new SleeperLiveWaiverPostTransactionRosterConvergence(ignored -> List.of(
            roster(2, "other", "300"),
            roster(6, "owner", "7049", "900")));

        var report = service.inspect(target(), completedSummary());

        assertEquals(SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceState.POST_TRANSACTION_ROSTER_CONVERGED,
            report.state());
        assertEquals(6, report.addCurrentRosterId());
        assertNull(report.dropCurrentRosterId());
        assertEquals(2, report.currentRosterCount());
        assertEquals("7049", report.addSleeperPlayerId());
        assertEquals("12503", report.dropSleeperPlayerId());
    }

    @Test
    void completedTransactionRemainsPropagationPendingAgainstLaggingPreTransactionRoster() throws Exception {
        var service = new SleeperLiveWaiverPostTransactionRosterConvergence(ignored -> List.of(
            roster(2, "other", "300"),
            roster(6, "owner", "12503", "900")));

        var report = service.inspect(target(), completedSummary());

        assertEquals(SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceState.POST_TRANSACTION_ROSTER_PROPAGATION_PENDING,
            report.state());
        assertNull(report.addCurrentRosterId());
        assertEquals(6, report.dropCurrentRosterId());
    }

    @Test
    void dropMayAlreadyBelongElsewhereOnceItIsAbsentFromTarget() throws Exception {
        var service = new SleeperLiveWaiverPostTransactionRosterConvergence(ignored -> List.of(
            roster(2, "other", "12503"),
            roster(6, "owner", "7049", "900")));

        var report = service.inspect(target(), completedSummary());

        assertEquals(SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceState.POST_TRANSACTION_ROSTER_CONVERGED,
            report.state());
        assertEquals(6, report.addCurrentRosterId());
        assertEquals(2, report.dropCurrentRosterId());
    }

    @Test
    void nonCompletedLifecycleDoesNotFetchRostersOrClaimConvergence() throws Exception {
        var service = new SleeperLiveWaiverPostTransactionRosterConvergence(ignored -> {
            throw new AssertionError("roster source must not be called");
        });

        var report = service.inspect(target(), currentSummary());

        assertEquals(SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceState.NOT_APPLICABLE,
            report.state());
        assertEquals(0, report.currentRosterCount());
    }

    @Test
    void duplicateLivePlayerOwnershipFailsClosed() {
        var service = new SleeperLiveWaiverPostTransactionRosterConvergence(ignored -> List.of(
            roster(2, "other", "7049"),
            roster(6, "owner", "7049")));

        var error = assertThrows(IllegalStateException.class,
            () -> service.inspect(target(), completedSummary()));

        assertEquals(true, error.getMessage().contains("appears on multiple rosters"));
    }

    @Test
    void missingTargetRosterFailsClosed() {
        var service = new SleeperLiveWaiverPostTransactionRosterConvergence(ignored -> List.of(
            roster(2, "other", "7049")));

        var error = assertThrows(IllegalStateException.class,
            () -> service.inspect(target(), completedSummary()));

        assertEquals(true, error.getMessage().contains("target roster is absent"));
    }

    @Test
    void targetRosterOwnerMismatchFailsClosed() {
        var service = new SleeperLiveWaiverPostTransactionRosterConvergence(ignored -> List.of(
            roster(6, "different-owner", "7049")));

        var error = assertThrows(IllegalStateException.class,
            () -> service.inspect(target(), completedSummary()));

        assertEquals(true, error.getMessage().contains("owner no longer matches BF-623"));
    }

    @Test
    void completedCompactStateWithoutCompletedBf629EvidenceFailsClosed() {
        var malformed = summary(
            SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.TRANSACTION_ALREADY_COMPLETE,
            SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED);
        var service = new SleeperLiveWaiverPostTransactionRosterConvergence(ignored -> List.of(
            roster(6, "owner", "7049")));

        var error = assertThrows(IllegalStateException.class,
            () -> service.inspect(target(), malformed));

        assertEquals(true, error.getMessage().contains("not backed by BF-629 completed transaction evidence"));
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

    private static SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport completedSummary() {
        return summary(
            SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.TRANSACTION_ALREADY_COMPLETE,
            SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.AUDITED_TRANSACTION_COMPLETE);
    }

    private static SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport currentSummary() {
        return summary(
            SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.CURRENT_AND_ACTIONABLE,
            SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED);
    }

    private static SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport summary(
        SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState state,
        SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState bf629State) {
        return new SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport(
            SleeperLiveWaiverLatestGovernedDecisionSummary.POLICY_ID,
            "league-butler",
            "owner",
            "league-sleeper",
            "Hard(CORE)-Dynasty",
            6,
            "nuke the whales",
            "audit-1",
            "2026-09-08T18:11:28Z",
            "RECOMMEND_ADD_DROP",
            "market-audited",
            "waiver-audited",
            new SleeperLiveWaiverLatestGovernedDecisionSummary.PlayerDisplay(
                "7049", "Jauan Jennings", "WR", "MIN"),
            new SleeperLiveWaiverLatestGovernedDecisionSummary.PlayerDisplay(
                "12503", "Isaiah Bond", "WR", "CLE"),
            bf629State,
            SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED,
            SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryState.EVIDENCE_AGE_REPORTED,
            "2026-09-08T20:58:37Z",
            10028L,
            "2026-09-08T18:04:21Z",
            10455L,
            "2026-09-08T18:02:57Z",
            10539L,
            state);
    }

    private static SleeperJsonParser.SleeperRoster roster(int rosterId, String ownerId, String... players) {
        return new SleeperJsonParser.SleeperRoster(
            rosterId, ownerId, List.of(players), List.of(), List.of(), List.of());
    }
}
