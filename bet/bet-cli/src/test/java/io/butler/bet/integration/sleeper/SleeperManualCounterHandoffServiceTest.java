package io.butler.bet.integration.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterAuthorizationGrantRepository;
import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionClaimRepository;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import io.butler.bet.intelligence.TradeCounterExecutionReadinessPolicy;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperManualCounterHandoffServiceTest {
    private static final String FINGERPRINT =
        "1f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";

    @TempDir
    Path tempDir;

    @Test
    void messageHandoffPresentsExactTrustedPayloadWithoutClaimingDelivery() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        var result = new SleeperManualCounterHandoffService(fixture.database())
            .prepare(fixture.claimId());

        assertEquals(SleeperManualCounterHandoffService.State.HANDOFF_READY, result.state());
        var handoff = result.handoff();
        assertEquals(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE, handoff.action());
        assertEquals("I'd counter if you add Player X.", handoff.payloadText());
        assertEquals(SleeperManualCounterHandoffService.ReconciliationMode.NO_OFFICIAL_READBACK,
            handoff.reconciliationMode());
        assertTrue(handoff.warning().contains("does not prove"));
        assertEquals(TradeCounterExecutionAttemptRepository.State.IN_FLIGHT,
            fixture.attempts().findByAttemptId(fixture.attemptId()).orElseThrow().state());
        assertFalse(fixture.grants().findById(fixture.grantId()).orElseThrow().consumed());
    }

    @Test
    void tradeHandoffPreservesJsonPayloadAndAdvertisesTransactionReadback() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE);
        var result = new SleeperManualCounterHandoffService(fixture.database())
            .prepare(fixture.claimId());

        var handoff = result.handoff();
        assertEquals(TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE, handoff.action());
        assertTrue(handoff.payloadText().startsWith("{"));
        assertEquals(SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK,
            handoff.reconciliationMode());
        assertEquals(fixture.claimId(), handoff.claimId());
        assertEquals(fixture.attemptId(), handoff.attemptId());
        assertEquals(fixture.grantId(), handoff.grantId());
    }

    @Test
    void missingClaimIsNotAvailable() throws Exception {
        Database database = new Database(tempDir.resolve("missing.db"));
        var result = new SleeperManualCounterHandoffService(database).prepare("missing-claim");

        assertEquals(SleeperManualCounterHandoffService.State.NOT_AVAILABLE, result.state());
        assertTrue(result.handoff() == null);
    }

    @Test
    void consumedGrantCannotBePresentedAsManualHandoff() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        var stored = fixture.grants().findById(fixture.grantId()).orElseThrow().grant();
        assertEquals(TradeCounterAuthorizationGrantRepository.ConsumptionResult.CONSUMED,
            fixture.grants().consume(
                stored.grantId(), stored.proposalFingerprint(), stored.action(), stored.destination(),
                Instant.parse("2026-09-04T20:02:00Z")));

        assertThrows(IllegalStateException.class, () ->
            new SleeperManualCounterHandoffService(fixture.database()).prepare(fixture.claimId()));
    }

    private Fixture fixture(TradeCounterAuthorizationPolicy.Action action) throws Exception {
        Database database = new Database(tempDir.resolve(action.name().toLowerCase() + ".db"));
        var grants = new TradeCounterAuthorizationGrantRepository(database);
        grants.initialize();
        var identity = identified();
        var destination = action == TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE
            ? new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22")
            : new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "league-1");
        var authorizationRequest = TradeCounterAuthorizationPolicy.request(identity, action, destination);
        var grant = TradeCounterAuthorizationPolicy.authorize(
            authorizationRequest, authorizationRequest.requiredConfirmation()).grant();
        grants.save(grant);

        var attempts = new TradeCounterExecutionAttemptRepository(database);
        var payloadKind = action == TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE
            ? TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT
            : TradeCounterExecutionAttemptRepository.PayloadKind.COUNTER_TRADE_REQUEST_JSON;
        String payload = action == TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE
            ? "I'd counter if you add Player X."
            : "{\"league_id\":\"league-1\",\"side_a\":[\"p1\"],\"side_b\":[\"p2\"]}";
        var attempt = attempts.prepare(
            grant.grantId(), payloadKind, payload, Instant.parse("2026-09-04T20:00:00Z")).attempt();
        var readiness = TradeCounterExecutionReadinessPolicy.assess(
            grant, false, true, identified());
        var claim = new TradeCounterExecutionClaimRepository(database).claim(
            attempt.attemptId(), readiness, Instant.parse("2026-09-04T20:01:00Z")).claim();
        return new Fixture(database, grants, attempts, grant.grantId(), attempt.attemptId(), claim.claimId());
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
            LocalDate.of(2026, 9, 1),
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            TradeCounterProposalIdentityPolicy.State.IDENTIFIED,
            TradeCounterProposalIdentityPolicy.ReasonCode.GOVERNED_COUNTER_IDENTIFIED,
            FINGERPRINT);
    }

    private record Fixture(
        Database database,
        TradeCounterAuthorizationGrantRepository grants,
        TradeCounterExecutionAttemptRepository attempts,
        String grantId,
        String attemptId,
        String claimId) {}
}
