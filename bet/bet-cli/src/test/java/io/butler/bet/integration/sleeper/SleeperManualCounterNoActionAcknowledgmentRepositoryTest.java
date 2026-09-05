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

class SleeperManualCounterNoActionAcknowledgmentRepositoryTest {
    private static final String FINGERPRINT =
        "1f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";
    private static final Instant PRESENTED_AT = Instant.parse("2026-09-05T00:20:00Z");
    private static final Instant ACKNOWLEDGED_AT = PRESENTED_AT.plusSeconds(20);
    private static final Instant RECORDED_AT = ACKNOWLEDGED_AT.plusSeconds(1);

    @TempDir
    Path tempDir;

    @Test
    void exactMessageNoActionEvidencePersistsWithoutTerminalizingAttemptOrGrant() throws Exception {
        Fixture fixture = fixture("message-record", TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        var repository = new SleeperManualCounterNoActionAcknowledgmentRepository(fixture.database());

        var result = repository.record(fixture.decision(), RECORDED_AT);

        assertEquals(SleeperManualCounterNoActionAcknowledgmentRepository.RecordState.RECORDED, result.state());
        assertEquals(fixture.claimId(), result.acknowledgment().claimId());
        assertEquals(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            result.acknowledgment().action());
        assertEquals(TradeCounterExecutionAttemptRepository.State.FAILED,
            result.acknowledgment().attemptTerminalState());
        assertEquals(ACKNOWLEDGED_AT, result.acknowledgment().acknowledgedAt());
        assertEquals(RECORDED_AT, result.acknowledgment().recordedAt());
        assertEquals(result.acknowledgment(), repository.findByClaimId(fixture.claimId()).orElseThrow());
        assertAttemptAndGrantRemainActive(fixture);
    }

    @Test
    void exactTradeNoActionEvidencePersistsWithoutTerminalizingAttemptOrGrant() throws Exception {
        Fixture fixture = fixture("trade-record", TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE);
        var repository = new SleeperManualCounterNoActionAcknowledgmentRepository(fixture.database());

        var result = repository.record(fixture.decision(), RECORDED_AT);

        assertEquals(SleeperManualCounterNoActionAcknowledgmentRepository.RecordState.RECORDED, result.state());
        assertEquals(TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE,
            result.acknowledgment().action());
        assertEquals(TradeCounterAuthorizationPolicy.DestinationType.LEAGUE,
            result.acknowledgment().destination().type());
        assertAttemptAndGrantRemainActive(fixture);
    }

    @Test
    void exactRepeatIsIdempotentAndPreservesFirstRecord() throws Exception {
        Fixture fixture = fixture("repeat", TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE);
        var repository = new SleeperManualCounterNoActionAcknowledgmentRepository(fixture.database());

        var first = repository.record(fixture.decision(), RECORDED_AT);
        var second = repository.record(fixture.decision(), RECORDED_AT.plusSeconds(60));

        assertEquals(SleeperManualCounterNoActionAcknowledgmentRepository.RecordState.RECORDED, first.state());
        assertEquals(SleeperManualCounterNoActionAcknowledgmentRepository.RecordState.ALREADY_RECORDED, second.state());
        assertEquals(first.acknowledgment().acknowledgmentId(), second.acknowledgment().acknowledgmentId());
        assertEquals(RECORDED_AT, second.acknowledgment().recordedAt());
        assertAttemptAndGrantRemainActive(fixture);
    }

    @Test
    void inexactNoActionEvidenceIsNotPersisted() throws Exception {
        Fixture fixture = fixture("not-eligible", TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        var handoff = fixture.handoff();
        var request = new SleeperManualCounterNoActionAcknowledgmentPolicy.AcknowledgmentRequest(
            handoff.grantId(), handoff.handoffId(), handoff.payloadSha256(),
            "NO_EXTERNAL_ACTION_TAKEN ", ACKNOWLEDGED_AT);
        var decision = SleeperManualCounterNoActionAcknowledgmentPolicy.acknowledge(handoff, request);
        var repository = new SleeperManualCounterNoActionAcknowledgmentRepository(fixture.database());

        var result = repository.record(decision, RECORDED_AT);

        assertEquals(SleeperManualCounterNoActionAcknowledgmentRepository.RecordState.NOT_ELIGIBLE, result.state());
        assertTrue(repository.findByClaimId(fixture.claimId()).isEmpty());
        assertAttemptAndGrantRemainActive(fixture);
    }

    @Test
    void existingSentMessageAcknowledgmentBlocksNoActionPersistence() throws Exception {
        Fixture fixture = fixture("sent-first", TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        var sentDecision = sentMessageDecision(fixture.handoff());
        var sentResult = new SleeperManualMessageAcknowledgmentRepository(fixture.database())
            .record(sentDecision, RECORDED_AT);
        assertEquals(SleeperManualMessageAcknowledgmentRepository.RecordState.RECORDED, sentResult.state());

        var result = new SleeperManualCounterNoActionAcknowledgmentRepository(fixture.database())
            .record(fixture.decision(), RECORDED_AT.plusSeconds(1));

        assertEquals(
            SleeperManualCounterNoActionAcknowledgmentRepository.RecordState.CONFLICTING_SUCCESS_EVIDENCE,
            result.state());
        assertTrue(new SleeperManualCounterNoActionAcknowledgmentRepository(fixture.database())
            .findByClaimId(fixture.claimId()).isEmpty());
        assertAttemptAndGrantRemainActive(fixture);
    }

    @Test
    void durableNoActionEvidenceBlocksLaterSentMessageAcknowledgmentAtDatabaseLayer() throws Exception {
        Fixture fixture = fixture("no-action-first", TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        var noActionRepository = new SleeperManualCounterNoActionAcknowledgmentRepository(fixture.database());
        assertEquals(
            SleeperManualCounterNoActionAcknowledgmentRepository.RecordState.RECORDED,
            noActionRepository.record(fixture.decision(), RECORDED_AT).state());

        var sentDecision = sentMessageDecision(fixture.handoff());
        assertThrows(SQLException.class, () ->
            new SleeperManualMessageAcknowledgmentRepository(fixture.database())
                .record(sentDecision, RECORDED_AT.plusSeconds(1)));

        assertTrue(new SleeperManualMessageAcknowledgmentRepository(fixture.database())
            .findByClaimId(fixture.claimId()).isEmpty());
        assertAttemptAndGrantRemainActive(fixture);
    }

    @Test
    void durableNoActionAcknowledgmentIsImmutable() throws Exception {
        Fixture fixture = fixture("immutable", TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE);
        var repository = new SleeperManualCounterNoActionAcknowledgmentRepository(fixture.database());
        repository.record(fixture.decision(), RECORDED_AT);

        try (var connection = fixture.database().openConnection(); var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE sleeper_manual_counter_no_action_acknowledgments SET destination_id='other'"
                    + " WHERE claim_id='" + fixture.claimId() + "'"));
        }
    }

    private Fixture fixture(String suffix, TradeCounterAuthorizationPolicy.Action action) throws Exception {
        Database database = new Database(tempDir.resolve("bf426-" + suffix + ".db"));
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
        assertTrue(presentation.state() == SleeperManualCounterHandoffRepository.RecordState.PRESENTED
            || presentation.state() == SleeperManualCounterHandoffRepository.RecordState.ALREADY_PRESENTED);
        var handoff = presentation.handoff();

        var noActionRequest = new SleeperManualCounterNoActionAcknowledgmentPolicy.AcknowledgmentRequest(
            handoff.grantId(), handoff.handoffId(), handoff.payloadSha256(),
            SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
            ACKNOWLEDGED_AT);
        var decision = SleeperManualCounterNoActionAcknowledgmentPolicy.acknowledge(
            handoff, noActionRequest);
        assertEquals(SleeperManualCounterNoActionAcknowledgmentPolicy.State.ACKNOWLEDGED, decision.state());

        return new Fixture(
            database, grants, attempts, grant.grantId(), attempt.attemptId(), claim.claimId(), handoff, decision);
    }

    private static SleeperManualMessageAcknowledgmentPolicy.Decision sentMessageDecision(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff) {
        var request = new SleeperManualMessageAcknowledgmentPolicy.AcknowledgmentRequest(
            handoff.grantId(), handoff.handoffId(), handoff.payloadSha256(),
            SleeperManualMessageAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
            ACKNOWLEDGED_AT);
        var decision = SleeperManualMessageAcknowledgmentPolicy.acknowledge(handoff, request);
        assertEquals(SleeperManualMessageAcknowledgmentPolicy.State.ACKNOWLEDGED, decision.state());
        return decision;
    }

    private static void assertAttemptAndGrantRemainActive(Fixture fixture) throws Exception {
        assertEquals(
            TradeCounterExecutionAttemptRepository.State.IN_FLIGHT,
            fixture.attempts().findByAttemptId(fixture.attemptId()).orElseThrow().state());
        assertFalse(fixture.grants().findById(fixture.grantId()).orElseThrow().consumed());
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
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        SleeperManualCounterNoActionAcknowledgmentPolicy.Decision decision) {}
}
