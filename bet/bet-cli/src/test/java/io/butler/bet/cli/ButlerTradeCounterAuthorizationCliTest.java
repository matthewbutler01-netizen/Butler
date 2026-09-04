package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterAuthorizationGrantRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterAuthorizationCliTest {
    private static final String FINGERPRINT =
        "1f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @TempDir
    Path tempDir;

    @Test
    void parsesMessageAuthorizationAfterSeparator() {
        var options = ButlerTradeCounterAuthorizationCli.parse(new String[] {
            "trade", "counter-authorize", "league-1", "2026", "p1", "p2", "side-a",
            "--", "message", "manager-22"
        });

        assertEquals("league-1", options.trade().leagueId());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            options.trade().perspective());
        assertEquals(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE, options.action());
        assertEquals(TradeCounterAuthorizationPolicy.DestinationType.MANAGER,
            options.destination().type());
        assertEquals("manager-22", options.destination().id());
        assertNull(options.confirmation());
    }

    @Test
    void preservesProposalOptionalArgumentsAndQuotedConfirmation() {
        String confirmation = "AUTHORIZE_ONCE action=SUBMIT_COUNTER_TRADE proposal=" + FINGERPRINT
            + " destination=LEAGUE:league-1";
        var options = ButlerTradeCounterAuthorizationCli.parse(new String[] {
            "trade", "counter-authorize", "league-1", "2026", "p1", "p2", "side-b",
            "source", "--minimum-as-of", "2026-09-01",
            "--", "submit", "league-1", "--confirm", confirmation
        });

        assertEquals("source", options.trade().source());
        assertEquals(AS_OF, options.trade().minimumAsOf());
        assertEquals(TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE, options.action());
        assertEquals(TradeCounterAuthorizationPolicy.DestinationType.LEAGUE,
            options.destination().type());
        assertEquals(confirmation, options.confirmation());
    }

    @Test
    void doesNotTrimOrNormalizeConfirmationText() {
        String confirmation = " AUTHORIZE_ONCE action=SEND_NEGOTIATION_MESSAGE proposal=" + FINGERPRINT
            + " destination=MANAGER:manager-22 ";
        var options = ButlerTradeCounterAuthorizationCli.parse(new String[] {
            "trade", "counter-authorize", "league-1", "2026", "p1", "p2", "side-a",
            "--", "message", "manager-22", "--confirm", confirmation
        });

        assertEquals(confirmation, options.confirmation());
    }

    @Test
    void requiresSeparatorAndKnownAction() {
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterAuthorizationCli.parse(
            new String[] {"trade", "counter-authorize", "league-1", "2026", "p1", "p2", "side-a"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterAuthorizationCli.parse(
            new String[] {"trade", "counter-authorize", "league-1", "2026", "p1", "p2", "side-a",
                "--", "anything", "manager-22"}));
    }

    @Test
    void requestOutputRequiresExactPhraseAndClaimsNoAction() {
        var request = request();
        String output = capture(() -> ButlerTradeCounterAuthorizationCli.printRequest(request));

        assertTrue(output.contains("Exact confirmation required:"));
        assertTrue(output.contains(request.requiredConfirmation()));
        assertTrue(output.contains("No authorization grant was created."));
        assertTrue(output.contains("No message or trade was sent or submitted."));
    }

    @Test
    void retainedBf385RendererStillClaimsNoExternalActionOrPersistence() {
        var request = request();
        var decision = TradeCounterAuthorizationPolicy.authorize(
            request, request.requiredConfirmation());
        String output = capture(() -> ButlerTradeCounterAuthorizationCli.printDecision(request, decision));

        assertTrue(output.contains("Authorization state: AUTHORIZED"));
        assertTrue(output.contains("Authorization maximum uses: 1"));
        assertTrue(output.contains("Grant is not persisted or consumed by this renderer."));
        assertTrue(output.contains("This command never sends a message or submits a trade."));
    }

    @Test
    void exactAuthorizedDecisionPersistsOneTrustedUnconsumedGrant() throws Exception {
        Database database = database();
        var request = request();
        var decision = TradeCounterAuthorizationPolicy.authorize(
            request, request.requiredConfirmation());

        var persistence = ButlerTradeCounterAuthorizationCli.persistAuthorization(database, decision);
        var repository = new TradeCounterAuthorizationGrantRepository(database);
        var stored = repository.findById(persistence.trustedGrantId()).orElseThrow();

        assertEquals(ButlerTradeCounterAuthorizationCli.PersistenceState.PERSISTED, persistence.state());
        assertEquals(decision.grant().grantId(), persistence.trustedGrantId());
        assertEquals(decision.grant(), stored.grant());
        assertFalse(stored.consumed());
    }

    @Test
    void rejectedDecisionPersistsNoGrant() throws Exception {
        Database database = database();
        var request = request();
        var rejected = TradeCounterAuthorizationPolicy.authorize(request, "approved");

        var persistence = ButlerTradeCounterAuthorizationCli.persistAuthorization(database, rejected);
        var repository = new TradeCounterAuthorizationGrantRepository(database);
        repository.initialize();

        assertEquals(ButlerTradeCounterAuthorizationCli.PersistenceState.NOT_APPLICABLE, persistence.state());
        assertNull(persistence.trustedGrantId());
        assertTrue(repository.findActive(
            FINGERPRINT,
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER,
                "manager-22")).isEmpty());
    }

    @Test
    void repeatedExactAuthorizationReusesExistingActiveTrustedGrant() throws Exception {
        Database database = database();
        var request = request();
        var firstDecision = TradeCounterAuthorizationPolicy.authorize(
            request, request.requiredConfirmation());
        var secondDecision = TradeCounterAuthorizationPolicy.authorize(
            request, request.requiredConfirmation());

        var first = ButlerTradeCounterAuthorizationCli.persistAuthorization(database, firstDecision);
        var second = ButlerTradeCounterAuthorizationCli.persistAuthorization(database, secondDecision);
        var repository = new TradeCounterAuthorizationGrantRepository(database);
        var active = repository.findActive(
            FINGERPRINT,
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER,
                "manager-22")).orElseThrow();

        assertEquals(ButlerTradeCounterAuthorizationCli.PersistenceState.PERSISTED, first.state());
        assertEquals(ButlerTradeCounterAuthorizationCli.PersistenceState.ACTIVE_GRANT_EXISTS, second.state());
        assertEquals(first.trustedGrantId(), second.trustedGrantId());
        assertEquals(first.trustedGrantId(), active.grant().grantId());
        assertFalse(active.consumed());
    }

    @Test
    void persistedOutputStillClaimsNoConsumptionOrExternalAction() throws Exception {
        Database database = database();
        var request = request();
        var decision = TradeCounterAuthorizationPolicy.authorize(
            request, request.requiredConfirmation());
        var persistence = ButlerTradeCounterAuthorizationCli.persistAuthorization(database, decision);

        String output = capture(() ->
            ButlerTradeCounterAuthorizationCli.printDecision(request, decision, persistence));

        assertTrue(output.contains("Authorization persistence: PERSISTED"));
        assertTrue(output.contains("Trusted authorization grant ID: " + persistence.trustedGrantId()));
        assertTrue(output.contains("persisted and remains unconsumed"));
        assertTrue(output.contains("No grant is consumed by this command."));
        assertTrue(output.contains("This command never sends a message or submits a trade."));
    }

    @Test
    void routerRecognizesCounterAuthorizeAsSeparateTarget() {
        assertEquals(ButlerCommandRouter.Route.TRADE_COUNTER_AUTHORIZATION,
            ButlerCommandRouter.route(new String[] {"trade", "counter-authorize"}));
        assertEquals(ButlerCommandRouter.Route.TRADE_COUNTER_PROPOSAL,
            ButlerCommandRouter.route(new String[] {"trade", "counter-proposal"}));
    }

    private Database database() {
        return new Database(tempDir.resolve("authorization-cli.db"));
    }

    private static TradeCounterAuthorizationPolicy.AuthorizationRequest request() {
        return TradeCounterAuthorizationPolicy.request(
            identifiedIdentity(),
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER,
                "manager-22"));
    }

    private static TradeCounterProposalIdentityPolicy.Identity identifiedIdentity() {
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
