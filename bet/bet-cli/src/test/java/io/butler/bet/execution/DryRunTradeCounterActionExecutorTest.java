package io.butler.bet.execution;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterAuthorizationGrantRepository;
import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionClaimRepository;
import io.butler.bet.data.TradeCounterExecutionRequestRepository;
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

class DryRunTradeCounterActionExecutorTest {
    private static final String FINGERPRINT =
        "1f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";
    private static final Instant PREPARED_AT = Instant.parse("2026-09-04T18:40:00Z");
    private static final Instant CLAIMED_AT = Instant.parse("2026-09-04T18:41:00Z");

    @TempDir
    Path tempDir;

    @Test
    void messageDryRunUsesOnlyPersistedClaimAttemptAndPayloadWithoutMutatingState() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        var request = fixture.requests().findByClaimId(fixture.claim().claimId()).orElseThrow();

        var result = new DryRunTradeCounterActionExecutor().execute(request);
        var attemptAfter = fixture.attempts().findByAttemptId(fixture.attempt().attemptId()).orElseThrow();
        var grantAfter = fixture.grants().findById(fixture.grant().grantId()).orElseThrow();

        assertEquals(TradeCounterExecutionRequestRepository.REQUEST_POLICY_ID, request.requestPolicyId());
        assertEquals(fixture.claim().claimId(), request.claimId());
        assertEquals(fixture.attempt().attemptId(), request.attemptId());
        assertEquals(fixture.grant().grantId(), request.grantId());
        assertEquals(fixture.grant().proposalFingerprint(), request.proposalFingerprint());
        assertEquals(fixture.grant().action(), request.action());
        assertEquals(fixture.grant().destination(), request.destination());
        assertEquals(fixture.attempt().payloadText(), request.payloadText());
        assertEquals(fixture.attempt().payloadSha256(), request.payloadSha256());

        assertEquals(DryRunTradeCounterActionExecutor.EXECUTOR_ID, result.executorId());
        assertEquals(TradeCounterActionExecutor.Mode.DRY_RUN, result.mode());
        assertEquals(TradeCounterActionExecutor.State.DRY_RUN_CONFIRMED, result.state());
        assertEquals(request.claimId(), result.claimId());
        assertEquals(request.attemptId(), result.attemptId());
        assertEquals(request.grantId(), result.grantId());
        assertEquals(request.payloadSha256(), result.payloadSha256());
        assertTrue(result.detail().contains("MANAGER:manager-22"));
        assertTrue(result.detail().contains("Dry run only"));

        assertEquals(TradeCounterExecutionAttemptRepository.State.IN_FLIGHT, attemptAfter.state());
        assertFalse(grantAfter.consumed());
        assertTrue(fixture.claims().findByAttemptId(fixture.attempt().attemptId()).isPresent());
    }

    @Test
    void tradeDryRunPreservesTrustedLeagueDestinationAndJsonPayload() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE);
        var request = fixture.requests().findByClaimId(fixture.claim().claimId()).orElseThrow();
        var result = new DryRunTradeCounterActionExecutor().execute(request);

        assertEquals(TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE, request.action());
        assertEquals(TradeCounterAuthorizationPolicy.DestinationType.LEAGUE,
            request.destination().type());
        assertEquals("league-1", request.destination().id());
        assertEquals(TradeCounterExecutionAttemptRepository.PayloadKind.COUNTER_TRADE_REQUEST_JSON,
            request.payloadKind());
        assertTrue(request.payloadText().startsWith("{"));
        assertTrue(result.detail().contains("submit counter trade"));
        assertTrue(result.detail().contains("LEAGUE:league-1"));
    }

    @Test
    void requestLoaderAcceptsOnlyClaimIdAndMissingClaimReturnsEmpty() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);

        assertTrue(fixture.requests().findByClaimId("missing-claim").isEmpty());
        assertTrue(fixture.requests().findByClaimId(fixture.claim().claimId()).isPresent());
    }

    @Test
    void consumedGrantCannotCrossExecutorRequestBoundary() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        assertEquals(TradeCounterAuthorizationGrantRepository.ConsumptionResult.CONSUMED,
            fixture.grants().consume(
                fixture.grant().grantId(),
                fixture.grant().proposalFingerprint(),
                fixture.grant().action(),
                fixture.grant().destination(),
                CLAIMED_AT.plusSeconds(1)));

        assertThrows(IllegalStateException.class, () ->
            fixture.requests().findByClaimId(fixture.claim().claimId()));
    }

    @Test
    void terminalAttemptCannotCrossExecutorRequestBoundary() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        assertEquals(TradeCounterExecutionAttemptRepository.TransitionState.TRANSITIONED,
            fixture.attempts().markUnknown(
                fixture.attempt().attemptId(),
                CLAIMED_AT.plusSeconds(5),
                "simulated unknown before dispatch load").state());

        assertThrows(IllegalStateException.class, () ->
            fixture.requests().findByClaimId(fixture.claim().claimId()));
    }

    @Test
    void executionRequestRejectsPayloadHashThatDoesNotMatchExactPayload() {
        var destination = new TradeCounterAuthorizationPolicy.Destination(
            TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22");

        assertThrows(IllegalArgumentException.class, () ->
            new TradeCounterExecutionRequestRepository.ExecutionRequest(
                TradeCounterExecutionRequestRepository.REQUEST_POLICY_ID,
                "claim-1",
                "attempt-1",
                "grant-1",
                FINGERPRINT,
                TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
                destination,
                TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
                "exact payload",
                FINGERPRINT));
    }

    private Fixture fixture(TradeCounterAuthorizationPolicy.Action action) throws Exception {
        Path path = tempDir.resolve(action.name().toLowerCase() + ".db");
        Database database = new Database(path);
        var grants = new TradeCounterAuthorizationGrantRepository(database);
        grants.initialize();
        var grant = authorizedGrant(action);
        grants.save(grant);

        var attempts = new TradeCounterExecutionAttemptRepository(database);
        var payloadKind = action == TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE
            ? TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT
            : TradeCounterExecutionAttemptRepository.PayloadKind.COUNTER_TRADE_REQUEST_JSON;
        String payload = action == TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE
            ? "I'd counter if you add Player X."
            : "{\"league_id\":\"league-1\",\"side_a\":[\"p1\"],\"side_b\":[\"p2\",\"p3\"]}";
        var attempt = attempts.prepare(grant.grantId(), payloadKind, payload, PREPARED_AT).attempt();

        var readiness = TradeCounterExecutionReadinessPolicy.assess(
            grant, false, true, identified(grant.proposalFingerprint()));
        var claims = new TradeCounterExecutionClaimRepository(database);
        var claim = claims.claim(attempt.attemptId(), readiness, CLAIMED_AT).claim();
        var requests = new TradeCounterExecutionRequestRepository(database);
        return new Fixture(database, grant, attempt, claim, grants, attempts, claims, requests);
    }

    private static TradeCounterAuthorizationPolicy.AuthorizationGrant authorizedGrant(
        TradeCounterAuthorizationPolicy.Action action) {
        var identity = identified(FINGERPRINT);
        var destination = action == TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE
            ? new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22")
            : new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "league-1");
        var request = TradeCounterAuthorizationPolicy.request(identity, action, destination);
        return TradeCounterAuthorizationPolicy.authorize(
            request, request.requiredConfirmation()).grant();
    }

    private static TradeCounterProposalIdentityPolicy.Identity identified(String fingerprint) {
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
            fingerprint);
    }

    private record Fixture(
        Database database,
        TradeCounterAuthorizationPolicy.AuthorizationGrant grant,
        TradeCounterExecutionAttemptRepository.ExecutionAttempt attempt,
        TradeCounterExecutionClaimRepository.ExecutionClaim claim,
        TradeCounterAuthorizationGrantRepository grants,
        TradeCounterExecutionAttemptRepository attempts,
        TradeCounterExecutionClaimRepository claims,
        TradeCounterExecutionRequestRepository requests) {}
}
