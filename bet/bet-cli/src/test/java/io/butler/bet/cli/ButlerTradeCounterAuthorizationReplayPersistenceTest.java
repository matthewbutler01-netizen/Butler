package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterAuthorizationReplayContextRepository;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import io.butler.bet.intelligence.TradeCounterMaterializedPackagePolicy;
import io.butler.bet.intelligence.TradeCounterProposalEnvelopePolicy;
import io.butler.bet.intelligence.TradeCounterProposalIdentityPolicy;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterAuthorizationReplayPersistenceTest {
    private static final String FINGERPRINT =
        "1f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";
    private static final TradeAssetAnalyzer.TradePackage SIDE_A =
        new TradeAssetAnalyzer.TradePackage(java.util.List.of("p1"), java.util.List.of("pick-a"));
    private static final TradeAssetAnalyzer.TradePackage SIDE_B =
        new TradeAssetAnalyzer.TradePackage(java.util.List.of("p2"), java.util.List.of("pick-b"));

    @TempDir
    Path tempDir;

    @Test
    void firstExactAuthorizationPersistsGrantAndReplayContext() throws Exception {
        Database database = database();
        var decision = authorizedDecision();

        var result = ButlerTradeCounterAuthorizationCli.persistAuthorizationWithReplay(
            database, decision, SIDE_A, SIDE_B);
        var replay = new TradeCounterAuthorizationReplayContextRepository(database)
            .findByGrantId(result.grantPersistence().trustedGrantId()).orElseThrow();

        assertEquals(ButlerTradeCounterAuthorizationCli.PersistenceState.PERSISTED,
            result.grantPersistence().state());
        assertEquals(TradeCounterAuthorizationReplayContextRepository.AttachmentResult.ATTACHED,
            result.replayAttachment());
        assertEquals(SIDE_A, replay.originalSideA());
        assertEquals(SIDE_B, replay.originalSideB());
    }

    @Test
    void repeatedExactAuthorizationReusesTrustedGrantAndExactReplayContext() throws Exception {
        Database database = database();
        var firstDecision = authorizedDecision();
        var secondDecision = authorizedDecision();

        var first = ButlerTradeCounterAuthorizationCli.persistAuthorizationWithReplay(
            database, firstDecision, SIDE_A, SIDE_B);
        var second = ButlerTradeCounterAuthorizationCli.persistAuthorizationWithReplay(
            database, secondDecision, SIDE_A, SIDE_B);

        assertEquals(ButlerTradeCounterAuthorizationCli.PersistenceState.PERSISTED,
            first.grantPersistence().state());
        assertEquals(ButlerTradeCounterAuthorizationCli.PersistenceState.ACTIVE_GRANT_EXISTS,
            second.grantPersistence().state());
        assertEquals(first.grantPersistence().trustedGrantId(),
            second.grantPersistence().trustedGrantId());
        assertEquals(TradeCounterAuthorizationReplayContextRepository.AttachmentResult.ALREADY_ATTACHED,
            second.replayAttachment());
    }

    @Test
    void olderActiveGrantWithoutReplayContextIsRepairedByExactReauthorization() throws Exception {
        Database database = database();
        var firstDecision = authorizedDecision();
        var legacy = ButlerTradeCounterAuthorizationCli.persistAuthorization(database, firstDecision);
        assertTrue(new TradeCounterAuthorizationReplayContextRepository(database)
            .findByGrantId(legacy.trustedGrantId()).isEmpty());

        var secondDecision = authorizedDecision();
        var repaired = ButlerTradeCounterAuthorizationCli.persistAuthorizationWithReplay(
            database, secondDecision, SIDE_A, SIDE_B);

        assertEquals(ButlerTradeCounterAuthorizationCli.PersistenceState.ACTIVE_GRANT_EXISTS,
            repaired.grantPersistence().state());
        assertEquals(legacy.trustedGrantId(), repaired.grantPersistence().trustedGrantId());
        assertEquals(TradeCounterAuthorizationReplayContextRepository.AttachmentResult.ATTACHED,
            repaired.replayAttachment());
    }

    @Test
    void rejectedAuthorizationPersistsNeitherGrantReplayBindingNorExternalAction() throws Exception {
        Database database = database();
        var request = request();
        var rejected = TradeCounterAuthorizationPolicy.authorize(request, "approved");

        var result = ButlerTradeCounterAuthorizationCli.persistAuthorizationWithReplay(
            database, rejected, SIDE_A, SIDE_B);

        assertEquals(ButlerTradeCounterAuthorizationCli.PersistenceState.NOT_APPLICABLE,
            result.grantPersistence().state());
        assertNull(result.grantPersistence().trustedGrantId());
        assertNull(result.replayAttachment());
    }

    @Test
    void differentReplayPackagesCannotRebindExistingTrustedGrant() throws Exception {
        Database database = database();
        var first = ButlerTradeCounterAuthorizationCli.persistAuthorizationWithReplay(
            database, authorizedDecision(), SIDE_A, SIDE_B);
        var differentSideA = TradeAssetAnalyzer.TradePackage.players(java.util.List.of("p9"));

        assertThrows(IllegalStateException.class, () ->
            ButlerTradeCounterAuthorizationCli.persistAuthorizationWithReplay(
                database, authorizedDecision(), differentSideA, SIDE_B));

        var replay = new TradeCounterAuthorizationReplayContextRepository(database)
            .findByGrantId(first.grantPersistence().trustedGrantId()).orElseThrow();
        assertEquals(SIDE_A, replay.originalSideA());
        assertEquals(SIDE_B, replay.originalSideB());
    }

    @Test
    void liveRendererReportsReplayBindingAndStillClaimsNoConsumption() throws Exception {
        Database database = database();
        var request = request();
        var decision = TradeCounterAuthorizationPolicy.authorize(
            request, request.requiredConfirmation());
        var persistence = ButlerTradeCounterAuthorizationCli.persistAuthorizationWithReplay(
            database, decision, SIDE_A, SIDE_B);

        String output = capture(() ->
            ButlerTradeCounterAuthorizationCli.printDecision(request, decision, persistence));

        assertTrue(output.contains("Authorization replay context: ATTACHED"));
        assertTrue(output.contains("Original Side A/Side B asset identities are bound"));
        assertTrue(output.contains("Replay persistence does not consume the grant"));
        assertTrue(output.contains("This command never sends a message or submits a trade."));
    }

    private Database database() {
        return new Database(tempDir.resolve("authorization-replay-cli.db"));
    }

    private static TradeCounterAuthorizationPolicy.AuthorizationDecision authorizedDecision() {
        var request = request();
        return TradeCounterAuthorizationPolicy.authorize(request, request.requiredConfirmation());
    }

    private static TradeCounterAuthorizationPolicy.AuthorizationRequest request() {
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
        return TradeCounterAuthorizationPolicy.request(
            identity,
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER,
                "manager-22"));
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
