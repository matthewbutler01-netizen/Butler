package io.butler.bet.cli;

import io.butler.bet.data.TradeCounterAuthorizationGrantRepository;
import io.butler.bet.data.TradeCounterAuthorizationReplayContextRepository;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import io.butler.bet.intelligence.TradeCounterExecutionReadinessPolicy;
import io.butler.bet.intelligence.TradeCounterMaterializedPackagePolicy;
import io.butler.bet.intelligence.TradeCounterProposalEnvelopePolicy;
import io.butler.bet.intelligence.TradeCounterProposalIdentityPolicy;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterReadinessCliTest {
    private static final String FINGERPRINT =
        "1f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);
    private static final TradeAssetAnalyzer.TradePackage SIDE_A =
        new TradeAssetAnalyzer.TradePackage(java.util.List.of("p1"), java.util.List.of("pick-a"));
    private static final TradeAssetAnalyzer.TradePackage SIDE_B =
        new TradeAssetAnalyzer.TradePackage(java.util.List.of("p2"), java.util.List.of("pick-b"));

    @Test
    void parsesExactlyOneTrustedGrantId() {
        assertEquals("grant-1", ButlerTradeCounterReadinessCli.parseGrantId(
            new String[] {"trade", "counter-readiness", " grant-1 "}));

        assertThrows(IllegalArgumentException.class, () ->
            ButlerTradeCounterReadinessCli.parseGrantId(
                new String[] {"trade", "counter-readiness"}));
        assertThrows(IllegalArgumentException.class, () ->
            ButlerTradeCounterReadinessCli.parseGrantId(
                new String[] {"trade", "counter-readiness", "grant-1", "extra"}));
        assertThrows(IllegalArgumentException.class, () ->
            ButlerTradeCounterReadinessCli.parseGrantId(
                new String[] {"trade", "counter-readiness", " "}));
    }

    @Test
    void replayOptionsUseOnlyTrustedGrantCoordinatesAndPersistedPackages() {
        var stored = storedGrant(null);
        var replay = new TradeCounterAuthorizationReplayContextRepository.ReplayContext(
            stored.grant().grantId(), SIDE_A, SIDE_B);

        var options = ButlerTradeCounterReadinessCli.replayOptions(stored, replay);

        assertEquals(stored.grant().leagueId(), options.leagueId());
        assertEquals(stored.grant().season(), options.season());
        assertEquals(SIDE_A, options.sideA());
        assertEquals(SIDE_B, options.sideB());
        assertEquals(stored.grant().perspective(), options.perspective());
        assertEquals(stored.grant().source(), options.source());
        assertEquals(stored.grant().minimumAsOfDate(), options.minimumAsOf());
    }

    @Test
    void replayContextCannotBeCrossBoundToAnotherGrant() {
        var stored = storedGrant(null);
        var replay = new TradeCounterAuthorizationReplayContextRepository.ReplayContext(
            "other-grant", SIDE_A, SIDE_B);

        assertThrows(IllegalStateException.class, () ->
            ButlerTradeCounterReadinessCli.replayOptions(stored, replay));
        assertThrows(IllegalStateException.class, () ->
            ButlerTradeCounterReadinessCli.assessReadiness(stored, replay, identified()));
    }

    @Test
    void consumedGrantBlocksWithoutReplayOrFreshIdentity() {
        var result = ButlerTradeCounterReadinessCli.assessReadiness(
            storedGrant(Instant.parse("2026-09-04T18:00:00Z")), null, null);

        assertEquals(TradeCounterExecutionReadinessPolicy.State.BLOCKED_ALREADY_CONSUMED,
            result.state());
        assertNull(result.revalidationState());
        assertThrows(IllegalArgumentException.class, () ->
            ButlerTradeCounterReadinessCli.assessReadiness(
                storedGrant(Instant.parse("2026-09-04T18:00:00Z")),
                replay(storedGrant(null).grant().grantId()),
                identified()));
    }

    @Test
    void missingReplayContextBlocksWithoutFreshIdentity() {
        var stored = storedGrant(null);
        var result = ButlerTradeCounterReadinessCli.assessReadiness(stored, null, null);

        assertEquals(TradeCounterExecutionReadinessPolicy.State.BLOCKED_MISSING_REPLAY_CONTEXT,
            result.state());
        assertNull(result.revalidationState());
        assertThrows(IllegalArgumentException.class, () ->
            ButlerTradeCounterReadinessCli.assessReadiness(stored, null, identified()));
    }

    @Test
    void activeStoredGrantAndReplayCanReportReady() {
        var stored = storedGrant(null);
        var result = ButlerTradeCounterReadinessCli.assessReadiness(
            stored,
            replay(stored.grant().grantId()),
            identified());

        assertEquals(TradeCounterExecutionReadinessPolicy.State.READY, result.state());
        assertEquals(TradeCounterAuthorizationPolicy.RevalidationState.MATCH,
            result.revalidationState());
        assertEquals(FINGERPRINT, result.freshFingerprint());
    }

    @Test
    void readinessOutputIsExplicitlyReadOnlyAndNoConsume() {
        var stored = storedGrant(null);
        var result = ButlerTradeCounterReadinessCli.assessReadiness(
            stored,
            replay(stored.grant().grantId()),
            identified());
        String output = capture(() -> ButlerTradeCounterReadinessCli.print(result));

        assertTrue(output.contains("Execution readiness: READY"));
        assertTrue(output.contains("Fresh revalidation: MATCH"));
        assertTrue(output.contains("Readiness never consumes the authorization grant."));
        assertTrue(output.contains("READY is evidence status only"));
        assertTrue(output.contains("never sends a message or submits a trade"));
    }

    @Test
    void missingGrantOutputClaimsNoExternalAction() {
        String output = capture(() -> ButlerTradeCounterReadinessCli.printNotFound("grant-404"));

        assertTrue(output.contains("trusted authorization grant was not found"));
        assertTrue(output.contains("No grant is consumed"));
        assertTrue(output.contains("no message or trade is sent or submitted"));
    }

    @Test
    void routerRecognizesReadinessSeparatelyFromAuthorizationAndProposal() {
        assertEquals(ButlerCommandRouter.Route.TRADE_COUNTER_READINESS,
            ButlerCommandRouter.route(new String[] {"trade", "counter-readiness"}));
        assertEquals(ButlerCommandRouter.Route.TRADE_COUNTER_AUTHORIZATION,
            ButlerCommandRouter.route(new String[] {"trade", "counter-authorize"}));
        assertEquals(ButlerCommandRouter.Route.TRADE_COUNTER_PROPOSAL,
            ButlerCommandRouter.route(new String[] {"trade", "counter-proposal"}));
    }

    private static TradeCounterAuthorizationGrantRepository.StoredGrant storedGrant(Instant consumedAt) {
        return new TradeCounterAuthorizationGrantRepository.StoredGrant(grant(), consumedAt);
    }

    private static TradeCounterAuthorizationReplayContextRepository.ReplayContext replay(String grantId) {
        return new TradeCounterAuthorizationReplayContextRepository.ReplayContext(grantId, SIDE_A, SIDE_B);
    }

    private static TradeCounterAuthorizationPolicy.AuthorizationGrant grant() {
        var request = TradeCounterAuthorizationPolicy.request(
            identified(),
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER,
                "manager-22"));
        return TradeCounterAuthorizationPolicy.authorize(
            request, request.requiredConfirmation()).grant();
    }

    private static TradeCounterProposalIdentityPolicy.Identity identified() {
        return new TradeCounterProposalIdentityPolicy.Identity(
            TradeCounterProposalIdentityPolicy.POLICY_ID,
            TradeCounterProposalEnvelopePolicy.POLICY_ID,
            TradeCounterMaterializedPackagePolicy.POLICY_ID,
            TradeCounterProposalIdentityPolicy.ALGORITHM,
            TradeCounterProposalIdentityPolicy.CANONICAL_VERSION,
            "league-1",
            2026,
            "source",
            AS_OF,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            TradeCounterProposalIdentityPolicy.State.IDENTIFIED,
            TradeCounterProposalIdentityPolicy.ReasonCode.GOVERNED_COUNTER_IDENTIFIED,
            FINGERPRINT);
    }

    private static String capture(Runnable runnable) {
        PrintStream original = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            runnable.run();
        } finally {
            System.setOut(original);
        }
        return bytes.toString();
    }
}
