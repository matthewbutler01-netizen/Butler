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
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperManualCounterNoActionOutcomeCoordinatorTest {
    private static final String FINGERPRINT =
        "1f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";
    private static final Instant PRESENTED_AT = Instant.parse("2026-09-05T00:30:00Z");
    private static final Instant ACKNOWLEDGED_AT = PRESENTED_AT.plusSeconds(20);
    private static final Instant RECORDED_AT = ACKNOWLEDGED_AT.plusSeconds(1);
    private static final Instant APPLIED_AT = RECORDED_AT.plusSeconds(1);

    @TempDir
    Path tempDir;

    @Test
    void messageNoActionEvidenceAtomicallyFinalizesFailedAndConsumesGrant() throws Exception {
        Fixture fixture = fixture("message-apply", TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        recordNoAction(fixture);

        var result = new SleeperManualCounterNoActionOutcomeCoordinator(fixture.database())
            .apply(fixture.claimId(), APPLIED_AT);

        assertEquals(SleeperManualCounterNoActionOutcomeCoordinator.ApplyState.APPLIED, result.state());
        assertEquals(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE, result.outcome().action());
        assertEquals(TradeCounterExecutionAttemptRepository.State.FAILED, result.outcome().terminalState());
        assertEquals("CONSUME", result.outcome().grantDisposition().name());
        assertTerminalAndConsumed(fixture);
    }

    @Test
    void tradeNoActionEvidenceAtomicallyFinalizesFailedAndConsumesGrant() throws Exception {
        Fixture fixture = fixture("trade-apply", TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE);
        recordNoAction(fixture);

        var result = new SleeperManualCounterNoActionOutcomeCoordinator(fixture.database())
            .apply(fixture.claimId(), APPLIED_AT);

        assertEquals(SleeperManualCounterNoActionOutcomeCoordinator.ApplyState.APPLIED, result.state());
        assertEquals(TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE, result.outcome().action());
        assertEquals(TradeCounterAuthorizationPolicy.DestinationType.LEAGUE,
            result.outcome().destination().type());
        assertTerminalAndConsumed(fixture);
    }

    @Test
    void exactRepeatIsIdempotent() throws Exception {
        Fixture fixture = fixture("repeat", TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE);
        recordNoAction(fixture);
        var coordinator = new SleeperManualCounterNoActionOutcomeCoordinator(fixture.database());

        var first = coordinator.apply(fixture.claimId(), APPLIED_AT);
        var second = coordinator.apply(fixture.claimId(), APPLIED_AT.plusSeconds(60));

        assertEquals(SleeperManualCounterNoActionOutcomeCoordinator.ApplyState.APPLIED, first.state());
        assertEquals(SleeperManualCounterNoActionOutcomeCoordinator.ApplyState.ALREADY_APPLIED, second.state());
        assertEquals(first.outcome().outcomeId(), second.outcome().outcomeId());
        assertEquals(APPLIED_AT, second.outcome().appliedAt());
        assertTerminalAndConsumed(fixture);
    }

    @Test
    void missingNoActionEvidenceCannotFinalize() throws Exception {
        Fixture fixture = fixture("missing", TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);

        var result = new SleeperManualCounterNoActionOutcomeCoordinator(fixture.database())
            .apply(fixture.claimId(), APPLIED_AT);

        assertEquals(SleeperManualCounterNoActionOutcomeCoordinator.ApplyState.NOT_FOUND, result.state());
        assertEquals(TradeCounterExecutionAttemptRepository.State.IN_FLIGHT,
            fixture.attempts().findByAttemptId(fixture.attemptId()).orElseThrow().state());
        assertFalse(fixture.grants().findById(fixture.grantId()).orElseThrow().consumed());
    }

    @Test
    void durableAcknowledgmentAloneCannotBypassTerminalOrConsumptionGuards() throws Exception {
        Fixture fixture = fixture("guards", TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE);
        recordNoAction(fixture);
        new SleeperManualCounterNoActionOutcomeCoordinator(fixture.database()).initialize();

        try (var connection = fixture.database().openConnection();
             var fail = connection.prepareStatement("""
                 UPDATE trade_counter_execution_attempts
                 SET state='FAILED', terminal_at=?, outcome_detail='bypass', updated_at=?
                 WHERE attempt_id=?
                 """);
             var consume = connection.prepareStatement("""
                 UPDATE trade_counter_authorization_grants SET consumed_at=? WHERE grant_id=?
                 """)) {
            fail.setString(1, APPLIED_AT.toString());
            fail.setString(2, APPLIED_AT.toString());
            fail.setString(3, fixture.attemptId());
            assertThrows(SQLException.class, fail::executeUpdate);

            consume.setString(1, APPLIED_AT.toString());
            consume.setString(2, fixture.grantId());
            assertThrows(SQLException.class, consume::executeUpdate);
        }

        assertEquals(TradeCounterExecutionAttemptRepository.State.IN_FLIGHT,
            fixture.attempts().findByAttemptId(fixture.attemptId()).orElseThrow().state());
        assertFalse(fixture.grants().findById(fixture.grantId()).orElseThrow().consumed());
    }

    @Test
    void terminalOutcomeIsImmutable() throws Exception {
        Fixture fixture = fixture("immutable", TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        recordNoAction(fixture);
        var coordinator = new SleeperManualCounterNoActionOutcomeCoordinator(fixture.database());
        coordinator.apply(fixture.claimId(), APPLIED_AT);

        try (var connection = fixture.database().openConnection(); var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE sleeper_manual_counter_no_action_terminal_outcomes SET destination_id='other'"
                    + " WHERE claim_id='" + fixture.claimId() + "'"));
        }
    }

    private void recordNoAction(Fixture fixture) throws Exception {
        var result = new SleeperManualCounterNoActionAcknowledgmentRepository(fixture.database())
            .record(fixture.decision(), RECORDED_AT);
        assertEquals(SleeperManualCounterNoActionAcknowledgmentRepository.RecordState.RECORDED, result.state());
        assertEquals(TradeCounterExecutionAttemptRepository.State.IN_FLIGHT,
            fixture.attempts().findByAttemptId(fixture.attemptId()).orElseThrow().state());
        assertFalse(fixture.grants().findById(fixture.grantId()).orElseThrow().consumed());
    }

    private static void assertTerminalAndConsumed(Fixture fixture) throws Exception {
        var attempt = fixture.attempts().findByAttemptId(fixture.attemptId()).orElseThrow();
        assertEquals(TradeCounterExecutionAttemptRepository.State.FAILED, attempt.state());
        assertTrue(attempt.outcomeDetail().contains("no external action was taken"));
        assertTrue(fixture.grants().findById(fixture.grantId()).orElseThrow().consumed());
    }

    private Fixture fixture(String suffix, TradeCounterAuthorizationPolicy.Action action) throws Exception {
        Database database = new Database(tempDir.resolve("bf427-" + suffix + ".db"));
        var grants = new TradeCounterAuthorizationGrantRepository(database);
        grants.initialize();
        var identity = identified();
        var destination = action == TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE
            ? new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22")
            : new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "league-1");
        var request = TradeCounterAuthorizationPolicy.request(identity, action, destination);
        var grant = TradeCounterAuthorizationPolicy.authorize(
            request, request.requiredConfirmation()).grant();
        grants.save(grant);

        var attempts = new TradeCounterExecutionAttemptRepository(database);
        var payloadKind = action == TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE
            ? TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT
            : TradeCounterExecutionAttemptRepository.PayloadKind.COUNTER_TRADE_REQUEST_JSON;
        String payload = action == TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE
            ? "I'd counter if you add Player X."
            : "{\"league_id\":\"league-1\",\"side_a\":[\"p1\"],\"side_b\":[\"p2\"]}";
        var attempt = attempts.prepare(
            grant.grantId(), payloadKind, payload, PRESENTED_AT.minusSeconds(60)).attempt();
        var readiness = TradeCounterExecutionReadinessPolicy.assess(
            grant, false, true, identified());
        var claim = new TradeCounterExecutionClaimRepository(database).claim(
            attempt.attemptId(), readiness, PRESENTED_AT.minusSeconds(30)).claim();
        var handoffRepository = new SleeperManualCounterHandoffRepository(database);
        var presentation = handoffRepository.recordPresented(claim.claimId(), PRESENTED_AT);
        var handoff = presentation.handoff();

        var noActionRequest = new SleeperManualCounterNoActionAcknowledgmentPolicy.AcknowledgmentRequest(
            handoff.grantId(), handoff.handoffId(), handoff.payloadSha256(),
            SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
            ACKNOWLEDGED_AT);
        var decision = SleeperManualCounterNoActionAcknowledgmentPolicy.acknowledge(
            handoff, noActionRequest);
        assertEquals(SleeperManualCounterNoActionAcknowledgmentPolicy.State.ACKNOWLEDGED, decision.state());

        return new Fixture(
            database, grants, attempts, grant.grantId(), attempt.attemptId(), claim.claimId(), decision);
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
        String claimId,
        SleeperManualCounterNoActionAcknowledgmentPolicy.Decision decision) {}
}
