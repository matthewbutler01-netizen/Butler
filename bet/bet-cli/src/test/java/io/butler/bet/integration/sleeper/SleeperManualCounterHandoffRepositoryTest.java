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

class SleeperManualCounterHandoffRepositoryTest {
    private static final String FINGERPRINT =
        "1f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";
    private static final Instant PRESENTED_AT = Instant.parse("2026-09-04T20:10:00Z");

    @TempDir
    Path tempDir;

    @Test
    void firstPresentationIsDurableWithoutChangingExecutionOrGrantState() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE);
        var repository = new SleeperManualCounterHandoffRepository(fixture.database());

        var result = repository.recordPresented(fixture.claimId(), PRESENTED_AT);

        assertEquals(SleeperManualCounterHandoffRepository.RecordState.PRESENTED, result.state());
        var stored = result.handoff();
        assertEquals(fixture.claimId(), stored.claimId());
        assertEquals(fixture.attemptId(), stored.attemptId());
        assertEquals(fixture.grantId(), stored.grantId());
        assertEquals(FINGERPRINT, stored.proposalFingerprint());
        assertEquals(SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK,
            stored.reconciliationMode());
        assertEquals(PRESENTED_AT, stored.presentedAt());
        assertEquals(stored, repository.findByClaimId(fixture.claimId()).orElseThrow());
        assertEquals(TradeCounterExecutionAttemptRepository.State.IN_FLIGHT,
            fixture.attempts().findByAttemptId(fixture.attemptId()).orElseThrow().state());
        assertFalse(fixture.grants().findById(fixture.grantId()).orElseThrow().consumed());
    }

    @Test
    void repeatedPresentationPreservesFirstTimestampAndIdentity() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        var repository = new SleeperManualCounterHandoffRepository(fixture.database());

        var first = repository.recordPresented(fixture.claimId(), PRESENTED_AT);
        var second = repository.recordPresented(
            fixture.claimId(), PRESENTED_AT.plusSeconds(3600));

        assertEquals(SleeperManualCounterHandoffRepository.RecordState.PRESENTED, first.state());
        assertEquals(SleeperManualCounterHandoffRepository.RecordState.ALREADY_PRESENTED, second.state());
        assertEquals(first.handoff().handoffId(), second.handoff().handoffId());
        assertEquals(PRESENTED_AT, second.handoff().presentedAt());
        assertEquals(SleeperManualCounterHandoffService.ReconciliationMode.NO_OFFICIAL_READBACK,
            second.handoff().reconciliationMode());
    }

    @Test
    void missingClaimDoesNotCreatePresentationRecord() throws Exception {
        Database database = new Database(tempDir.resolve("missing.db"));
        var repository = new SleeperManualCounterHandoffRepository(database);

        var result = repository.recordPresented("missing-claim", PRESENTED_AT);

        assertEquals(SleeperManualCounterHandoffRepository.RecordState.NOT_AVAILABLE, result.state());
        assertTrue(result.handoff() == null);
        assertTrue(repository.findByClaimId("missing-claim").isEmpty());
    }

    @Test
    void consumedGrantCannotCreatePresentationRecord() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        var grant = fixture.grants().findById(fixture.grantId()).orElseThrow().grant();
        assertEquals(TradeCounterAuthorizationGrantRepository.ConsumptionResult.CONSUMED,
            fixture.grants().consume(
                grant.grantId(), grant.proposalFingerprint(), grant.action(), grant.destination(),
                PRESENTED_AT.minusSeconds(1)));

        var repository = new SleeperManualCounterHandoffRepository(fixture.database());
        assertThrows(IllegalStateException.class, () ->
            repository.recordPresented(fixture.claimId(), PRESENTED_AT));
        assertTrue(repository.findByClaimId(fixture.claimId()).isEmpty());
    }

    @Test
    void durablePresentationRowIsImmutableAtDatabaseLayer() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE);
        var repository = new SleeperManualCounterHandoffRepository(fixture.database());
        repository.recordPresented(fixture.claimId(), PRESENTED_AT);

        try (var connection = fixture.database().openConnection();
             var statement = connection.prepareStatement("""
                 UPDATE sleeper_manual_counter_handoffs
                 SET presented_at = ?
                 WHERE claim_id = ?
                 """)) {
            statement.setString(1, PRESENTED_AT.plusSeconds(1).toString());
            statement.setString(2, fixture.claimId());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    private Fixture fixture(TradeCounterAuthorizationPolicy.Action action) throws Exception {
        Database database = new Database(tempDir.resolve(action.name().toLowerCase() + "-journal.db"));
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
