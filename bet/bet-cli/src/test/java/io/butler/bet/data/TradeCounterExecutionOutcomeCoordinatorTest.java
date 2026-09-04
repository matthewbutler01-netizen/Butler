package io.butler.bet.data;

import io.butler.bet.execution.DryRunTradeCounterActionExecutor;
import io.butler.bet.execution.TradeCounterActionExecutor;
import io.butler.bet.execution.TradeCounterExecutionOutcomePolicy;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import io.butler.bet.intelligence.TradeCounterExecutionReadinessPolicy;
import io.butler.bet.intelligence.TradeCounterMaterializedPackagePolicy;
import io.butler.bet.intelligence.TradeCounterProposalEnvelopePolicy;
import io.butler.bet.intelligence.TradeCounterProposalIdentityPolicy;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeCounterExecutionOutcomeCoordinatorTest {
    private static final String FINGERPRINT =
        "1f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";
    private static final Instant PREPARED_AT = Instant.parse("2026-09-04T19:00:00Z");
    private static final Instant CLAIMED_AT = Instant.parse("2026-09-04T19:01:00Z");
    private static final Instant OUTCOME_AT = Instant.parse("2026-09-04T19:02:00Z");
    private static final Instant RESOLVED_AT = Instant.parse("2026-09-04T19:03:00Z");

    @TempDir
    Path tempDir;

    @Test
    void dryRunDirectivePerformsNoDurableMutation() throws Exception {
        var fixture = fixture("dry-run");
        var dryRunResult = new DryRunTradeCounterActionExecutor().execute(fixture.request());
        var directive = TradeCounterExecutionOutcomePolicy.classify(fixture.request(), dryRunResult);

        var result = fixture.coordinator().apply(directive, OUTCOME_AT);

        assertEquals(TradeCounterExecutionOutcomeCoordinator.ApplyState.DRY_RUN_NO_MUTATION,
            result.state());
        assertTrue(fixture.coordinator().findOutcomeByAttemptId(fixture.attempt().attemptId()).isEmpty());
        assertEquals(TradeCounterExecutionAttemptRepository.State.IN_FLIGHT,
            fixture.attempts().findByAttemptId(fixture.attempt().attemptId()).orElseThrow().state());
        assertFalse(fixture.grants().findById(fixture.grant().grantId()).orElseThrow().consumed());
    }

    @Test
    void confirmedSuccessAtomicallyPersistsOutcomeTerminalStateAndGrantConsumption() throws Exception {
        var fixture = fixture("success");
        var directive = liveDirective(fixture, TradeCounterActionExecutor.State.DISPATCHED,
            "platform accepted request id remote-77");

        var result = fixture.coordinator().apply(directive, OUTCOME_AT);

        assertEquals(TradeCounterExecutionOutcomeCoordinator.ApplyState.APPLIED, result.state());
        assertNotNull(result.outcome());
        assertEquals(TradeCounterExecutionOutcomePolicy.OutcomeState.CONFIRMED_SUCCESS,
            result.outcome().outcomeState());
        assertEquals(TradeCounterExecutionAttemptRepository.State.SUCCEEDED,
            fixture.attempts().findByAttemptId(fixture.attempt().attemptId()).orElseThrow().state());
        assertTrue(fixture.grants().findById(fixture.grant().grantId()).orElseThrow().consumed());
    }

    @Test
    void definiteFailureClosesOneShotGrantAndRequiresFreshAuthorizationForRetry() throws Exception {
        var fixture = fixture("failure");
        var directive = liveDirective(fixture, TradeCounterActionExecutor.State.DEFINITE_FAILURE,
            "platform rejected before creating any remote action");

        var result = fixture.coordinator().apply(directive, OUTCOME_AT);

        assertEquals(TradeCounterExecutionOutcomeCoordinator.ApplyState.APPLIED, result.state());
        assertEquals(TradeCounterExecutionAttemptRepository.State.FAILED,
            fixture.attempts().findByAttemptId(fixture.attempt().attemptId()).orElseThrow().state());
        assertTrue(fixture.grants().findById(fixture.grant().grantId()).orElseThrow().consumed());
        assertEquals(TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME,
            result.outcome().grantDisposition());
    }

    @Test
    void unknownPersistsTerminalAuditStateAndRetainsActiveRetryLock() throws Exception {
        var fixture = fixture("unknown");
        var directive = liveDirective(fixture, TradeCounterActionExecutor.State.UNKNOWN,
            "network timed out after request transmission");

        var result = fixture.coordinator().apply(directive, OUTCOME_AT);

        assertEquals(TradeCounterExecutionOutcomeCoordinator.ApplyState.APPLIED, result.state());
        assertEquals(TradeCounterExecutionAttemptRepository.State.UNKNOWN,
            fixture.attempts().findByAttemptId(fixture.attempt().attemptId()).orElseThrow().state());
        assertFalse(fixture.grants().findById(fixture.grant().grantId()).orElseThrow().consumed());
        assertTrue(result.outcome().reconciliationRequired());
        assertThrows(SQLException.class, () -> fixture.grants().consume(
            fixture.grant().grantId(),
            fixture.grant().proposalFingerprint(),
            fixture.grant().action(),
            fixture.grant().destination(),
            OUTCOME_AT.plusSeconds(1)));
    }

    @Test
    void unknownRemoteActionResolutionConsumesGrantButPreservesHistoricalUnknownAttempt() throws Exception {
        var fixture = fixture("unknown-success-resolution");
        var unknownDirective = liveDirective(fixture, TradeCounterActionExecutor.State.UNKNOWN,
            "timeout after dispatch");
        fixture.coordinator().apply(unknownDirective, OUTCOME_AT);
        var resolution = TradeCounterExecutionOutcomePolicy.reconcileUnknown(
            unknownDirective,
            TradeCounterExecutionOutcomePolicy.UnknownResolution.REMOTE_ACTION_CONFIRMED,
            "platform audit found remote transaction tx-99");

        var result = fixture.coordinator().resolveUnknown(resolution, RESOLVED_AT);

        assertEquals(TradeCounterExecutionOutcomeCoordinator.ResolutionState.RESOLVED, result.state());
        assertTrue(result.resolution().remoteActionConfirmed());
        assertTrue(fixture.grants().findById(fixture.grant().grantId()).orElseThrow().consumed());
        assertEquals(TradeCounterExecutionAttemptRepository.State.UNKNOWN,
            fixture.attempts().findByAttemptId(fixture.attempt().attemptId()).orElseThrow().state());
    }

    @Test
    void unknownNoActionResolutionClosesOldGrantWithoutRewritingUnknownHistory() throws Exception {
        var fixture = fixture("unknown-no-action-resolution");
        var unknownDirective = liveDirective(fixture, TradeCounterActionExecutor.State.UNKNOWN,
            "timeout after dispatch");
        fixture.coordinator().apply(unknownDirective, OUTCOME_AT);
        var resolution = TradeCounterExecutionOutcomePolicy.reconcileUnknown(
            unknownDirective,
            TradeCounterExecutionOutcomePolicy.UnknownResolution.REMOTE_NO_ACTION_CONFIRMED,
            "platform audit proves no message or transaction exists");

        var result = fixture.coordinator().resolveUnknown(resolution, RESOLVED_AT);

        assertEquals(TradeCounterExecutionOutcomeCoordinator.ResolutionState.RESOLVED, result.state());
        assertFalse(result.resolution().remoteActionConfirmed());
        assertTrue(fixture.grants().findById(fixture.grant().grantId()).orElseThrow().consumed());
        assertEquals(TradeCounterExecutionAttemptRepository.State.UNKNOWN,
            fixture.attempts().findByAttemptId(fixture.attempt().attemptId()).orElseThrow().state());
    }

    @Test
    void repeatedSameOutcomeAndResolutionAreIdempotent() throws Exception {
        var fixture = fixture("idempotent");
        var unknownDirective = liveDirective(fixture, TradeCounterActionExecutor.State.UNKNOWN,
            "uncertain response");

        var first = fixture.coordinator().apply(unknownDirective, OUTCOME_AT);
        var second = fixture.coordinator().apply(unknownDirective, OUTCOME_AT.plusSeconds(10));

        assertEquals(TradeCounterExecutionOutcomeCoordinator.ApplyState.APPLIED, first.state());
        assertEquals(TradeCounterExecutionOutcomeCoordinator.ApplyState.ALREADY_APPLIED, second.state());
        assertEquals(first.outcome().outcomeId(), second.outcome().outcomeId());

        var resolution = TradeCounterExecutionOutcomePolicy.reconcileUnknown(
            unknownDirective,
            TradeCounterExecutionOutcomePolicy.UnknownResolution.REMOTE_NO_ACTION_CONFIRMED,
            "verified absent remotely");
        var resolved = fixture.coordinator().resolveUnknown(resolution, RESOLVED_AT);
        var resolvedAgain = fixture.coordinator().resolveUnknown(resolution, RESOLVED_AT.plusSeconds(10));

        assertEquals(TradeCounterExecutionOutcomeCoordinator.ResolutionState.RESOLVED, resolved.state());
        assertEquals(TradeCounterExecutionOutcomeCoordinator.ResolutionState.ALREADY_RESOLVED,
            resolvedAgain.state());
        assertEquals(resolved.resolution().resolutionId(), resolvedAgain.resolution().resolutionId());
    }

    @Test
    void conflictingOutcomeAfterDurableOutcomeFailsClosed() throws Exception {
        var fixture = fixture("conflicting");
        var first = liveDirective(fixture, TradeCounterActionExecutor.State.UNKNOWN,
            "uncertain response one");
        fixture.coordinator().apply(first, OUTCOME_AT);
        var conflictingResult = new TradeCounterActionExecutor.ExecutionResult(
            "fake-live-executor",
            TradeCounterActionExecutor.Mode.LIVE,
            TradeCounterActionExecutor.State.UNKNOWN,
            fixture.request().claimId(),
            fixture.request().attemptId(),
            fixture.request().grantId(),
            fixture.request().payloadSha256(),
            "uncertain response two");
        var conflicting = TradeCounterExecutionOutcomePolicy.classify(fixture.request(), conflictingResult);

        var result = fixture.coordinator().apply(conflicting, OUTCOME_AT.plusSeconds(1));

        assertEquals(TradeCounterExecutionOutcomeCoordinator.ApplyState.MISMATCH, result.state());
    }

    @Test
    void coordinatorInitializationBlocksDirectTerminalizationBypass() throws Exception {
        var fixture = fixture("terminal-bypass");

        assertThrows(SQLException.class, () -> fixture.attempts().markUnknown(
            fixture.attempt().attemptId(), OUTCOME_AT, "bypass attempt"));
        assertEquals(TradeCounterExecutionAttemptRepository.State.IN_FLIGHT,
            fixture.attempts().findByAttemptId(fixture.attempt().attemptId()).orElseThrow().state());
    }

    @Test
    void coordinatorInitializationBlocksDirectGrantConsumptionBypass() throws Exception {
        var fixture = fixture("consume-bypass");

        assertThrows(SQLException.class, () -> fixture.grants().consume(
            fixture.grant().grantId(),
            fixture.grant().proposalFingerprint(),
            fixture.grant().action(),
            fixture.grant().destination(),
            OUTCOME_AT));
        assertFalse(fixture.grants().findById(fixture.grant().grantId()).orElseThrow().consumed());
    }

    private TradeCounterExecutionOutcomePolicy.Directive liveDirective(
        Fixture fixture,
        TradeCounterActionExecutor.State state,
        String detail) {
        var result = new TradeCounterActionExecutor.ExecutionResult(
            "fake-live-executor",
            TradeCounterActionExecutor.Mode.LIVE,
            state,
            fixture.request().claimId(),
            fixture.request().attemptId(),
            fixture.request().grantId(),
            fixture.request().payloadSha256(),
            detail);
        return TradeCounterExecutionOutcomePolicy.classify(fixture.request(), result);
    }

    private Fixture fixture(String name) throws Exception {
        Path path = tempDir.resolve(name + ".db");
        Database database = new Database(path);
        var grants = new TradeCounterAuthorizationGrantRepository(database);
        grants.initialize();
        var grant = authorizedGrant();
        grants.save(grant);

        var attempts = new TradeCounterExecutionAttemptRepository(database);
        var attempt = attempts.prepare(
            grant.grantId(),
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
            "I'd counter if you add Player X.",
            PREPARED_AT).attempt();
        var readiness = TradeCounterExecutionReadinessPolicy.assess(
            grant, false, true, identified(grant.proposalFingerprint()));
        var claims = new TradeCounterExecutionClaimRepository(database);
        var claim = claims.claim(attempt.attemptId(), readiness, CLAIMED_AT).claim();
        var requests = new TradeCounterExecutionRequestRepository(database);
        var request = requests.findByClaimId(claim.claimId()).orElseThrow();
        var coordinator = new TradeCounterExecutionOutcomeCoordinator(database);
        coordinator.initialize();
        return new Fixture(database, grant, attempt, claim, request, grants, attempts, coordinator);
    }

    private static TradeCounterAuthorizationPolicy.AuthorizationGrant authorizedGrant() {
        var identity = identified(FINGERPRINT);
        var destination = new TradeCounterAuthorizationPolicy.Destination(
            TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22");
        var request = TradeCounterAuthorizationPolicy.request(
            identity,
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            destination);
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
        TradeCounterExecutionRequestRepository.ExecutionRequest request,
        TradeCounterAuthorizationGrantRepository grants,
        TradeCounterExecutionAttemptRepository attempts,
        TradeCounterExecutionOutcomeCoordinator coordinator) {}
}