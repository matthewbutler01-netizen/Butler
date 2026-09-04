package io.butler.bet.data;

import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import io.butler.bet.intelligence.TradeCounterMaterializedPackagePolicy;
import io.butler.bet.intelligence.TradeCounterProposalEnvelopePolicy;
import io.butler.bet.intelligence.TradeCounterProposalIdentityPolicy;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeCounterAuthorizationReplayContextRepositoryTest {
    private static final String FINGERPRINT =
        "1f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";

    @TempDir
    Path tempDir;

    @Test
    void attachesAndRoundTripsMixedOriginalPackages() throws Exception {
        var fixture = fixture();
        var sideA = new TradeAssetAnalyzer.TradePackage(
            java.util.List.of("p1", "p2"), java.util.List.of("pick-a"));
        var sideB = new TradeAssetAnalyzer.TradePackage(
            java.util.List.of("p3"), java.util.List.of("pick-b", "pick-c"));

        var result = fixture.replay().attach(fixture.grant().grantId(), sideA, sideB);
        var stored = fixture.replay().findByGrantId(fixture.grant().grantId()).orElseThrow();

        assertEquals(TradeCounterAuthorizationReplayContextRepository.AttachmentResult.ATTACHED, result);
        assertEquals(fixture.grant().grantId(), stored.grantId());
        assertEquals(sideA, stored.originalSideA());
        assertEquals(sideB, stored.originalSideB());
    }

    @Test
    void exactReattachmentIsIdempotentButDifferentContextCannotReplaceIt() throws Exception {
        var fixture = fixture();
        var sideA = TradeAssetAnalyzer.TradePackage.players(java.util.List.of("p1"));
        var sideB = TradeAssetAnalyzer.TradePackage.picks(java.util.List.of("pick-b"));

        assertEquals(TradeCounterAuthorizationReplayContextRepository.AttachmentResult.ATTACHED,
            fixture.replay().attach(fixture.grant().grantId(), sideA, sideB));
        assertEquals(TradeCounterAuthorizationReplayContextRepository.AttachmentResult.ALREADY_ATTACHED,
            fixture.replay().attach(fixture.grant().grantId(), sideA, sideB));

        assertThrows(IllegalStateException.class, () -> fixture.replay().attach(
            fixture.grant().grantId(),
            TradeAssetAnalyzer.TradePackage.players(java.util.List.of("p9")),
            sideB));

        var stored = fixture.replay().findByGrantId(fixture.grant().grantId()).orElseThrow();
        assertEquals(sideA, stored.originalSideA());
        assertEquals(sideB, stored.originalSideB());
    }

    @Test
    void missingTrustedGrantCannotReceiveReplayContext() throws Exception {
        Database database = database();
        var replay = new TradeCounterAuthorizationReplayContextRepository(database);

        assertThrows(IllegalArgumentException.class, () -> replay.attach(
            "missing-grant",
            TradeAssetAnalyzer.TradePackage.players(java.util.List.of("p1")),
            TradeAssetAnalyzer.TradePackage.players(java.util.List.of("p2"))));
        assertTrue(replay.findByGrantId("missing-grant").isEmpty());
    }

    @Test
    void consumedGrantCannotReceiveReplayContext() throws Exception {
        var fixture = fixture();
        assertEquals(TradeCounterAuthorizationGrantRepository.ConsumptionResult.CONSUMED,
            fixture.grants().consume(
                fixture.grant().grantId(),
                fixture.grant().proposalFingerprint(),
                fixture.grant().action(),
                fixture.grant().destination(),
                Instant.parse("2026-09-04T18:00:00Z")));

        assertThrows(IllegalStateException.class, () -> fixture.replay().attach(
            fixture.grant().grantId(),
            TradeAssetAnalyzer.TradePackage.players(java.util.List.of("p1")),
            TradeAssetAnalyzer.TradePackage.players(java.util.List.of("p2"))));
        assertTrue(fixture.replay().findByGrantId(fixture.grant().grantId()).isEmpty());
    }

    @Test
    void invalidOrOverlappingPackagesFailBeforePersistence() throws Exception {
        var fixture = fixture();

        assertThrows(IllegalArgumentException.class, () -> fixture.replay().attach(
            fixture.grant().grantId(),
            new TradeAssetAnalyzer.TradePackage(java.util.List.of(), java.util.List.of()),
            TradeAssetAnalyzer.TradePackage.players(java.util.List.of("p2"))));
        assertThrows(IllegalArgumentException.class, () -> fixture.replay().attach(
            fixture.grant().grantId(),
            TradeAssetAnalyzer.TradePackage.players(java.util.List.of("p1")),
            TradeAssetAnalyzer.TradePackage.players(java.util.List.of("p1"))));
        assertThrows(IllegalArgumentException.class, () -> fixture.replay().attach(
            fixture.grant().grantId(),
            new TradeAssetAnalyzer.TradePackage(java.util.List.of("p1", "p1"), java.util.List.of()),
            TradeAssetAnalyzer.TradePackage.players(java.util.List.of("p2"))));

        assertTrue(fixture.replay().findByGrantId(fixture.grant().grantId()).isEmpty());
    }

    private Fixture fixture() throws Exception {
        Database database = database();
        var grants = new TradeCounterAuthorizationGrantRepository(database);
        grants.initialize();
        var grant = authorizedGrant();
        grants.save(grant);
        var replay = new TradeCounterAuthorizationReplayContextRepository(database);
        replay.initialize();
        return new Fixture(grants, replay, grant);
    }

    private Database database() {
        return new Database(tempDir.resolve("authorization-replay.db"));
    }

    private static TradeCounterAuthorizationPolicy.AuthorizationGrant authorizedGrant() {
        var identity = new TradeCounterProposalIdentityPolicy.Identity(
            TradeCounterProposalIdentityPolicy.POLICY_ID,
            TradeCounterProposalEnvelopePolicy.POLICY_ID,
            TradeCounterMaterializedPackagePolicy.POLICY_ID,
            TradeCounterProposalIdentityPolicy.ALGORITHM,
            TradeCounterProposalIdentityPolicy.CANONICAL_VERSION,
            "league-1",
            2026,
            "source",
            LocalDate.of(2026, 9, 1),
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            TradeCounterProposalIdentityPolicy.State.IDENTIFIED,
            TradeCounterProposalIdentityPolicy.ReasonCode.GOVERNED_COUNTER_IDENTIFIED,
            FINGERPRINT);
        var destination = new TradeCounterAuthorizationPolicy.Destination(
            TradeCounterAuthorizationPolicy.DestinationType.MANAGER,
            "manager-22");
        var request = TradeCounterAuthorizationPolicy.request(
            identity,
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            destination);
        return TradeCounterAuthorizationPolicy.authorize(
            request, request.requiredConfirmation()).grant();
    }

    private record Fixture(
        TradeCounterAuthorizationGrantRepository grants,
        TradeCounterAuthorizationReplayContextRepository replay,
        TradeCounterAuthorizationPolicy.AuthorizationGrant grant) {}
}
