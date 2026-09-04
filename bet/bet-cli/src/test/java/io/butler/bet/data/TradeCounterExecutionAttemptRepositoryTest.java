package io.butler.bet.data;

import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeCounterExecutionAttemptRepositoryTest {
    private static final String FINGERPRINT =
        "1f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";
    private static final String MESSAGE = "I'd counter if you add Player X.";
    private static final String MESSAGE_HASH =
        "192d6d54fd1e17c670b86edba1240bbcdd22ce6a9d0a7bd52ad95da61f562446";
    private static final Instant PREPARED_AT = Instant.parse("2026-09-04T18:20:00Z");

    @TempDir
    Path tempDir;

    @Test
    void preparesAndRoundTripsExactTrustedMessageIntent() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);

        var result = fixture.attempts().prepare(
            fixture.grant().grantId(),
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
            MESSAGE,
            PREPARED_AT);
        var stored = fixture.attempts().findByAttemptId(result.attempt().attemptId()).orElseThrow();

        assertEquals(TradeCounterExecutionAttemptRepository.PreparationState.PREPARED, result.state());
        assertEquals(TradeCounterExecutionAttemptRepository.JOURNAL_POLICY_ID, stored.journalPolicyId());
        assertEquals(fixture.grant().grantId(), stored.grantId());
        assertEquals(fixture.grant().policyId(), stored.authorizationPolicyId());
        assertEquals(fixture.grant().proposalFingerprint(), stored.proposalFingerprint());
        assertEquals(fixture.grant().action(), stored.action());
        assertEquals(fixture.grant().destination(), stored.destination());
        assertEquals(TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
            stored.payloadKind());
        assertEquals(MESSAGE, stored.payloadText());
        assertEquals(MESSAGE_HASH, stored.payloadSha256());
        assertEquals(TradeCounterExecutionAttemptRepository.State.PREPARED, stored.state());
        assertEquals(PREPARED_AT, stored.preparedAt());
        assertEquals(PREPARED_AT, stored.updatedAt());
        assertNull(stored.inFlightAt());
        assertNull(stored.terminalAt());
        assertNull(stored.outcomeDetail());
    }

    @Test
    void exactRepeatedPreparationReturnsSameAttemptButPayloadDriftFailsClosed() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);

        var first = fixture.attempts().prepare(
            fixture.grant().grantId(),
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
            MESSAGE,
            PREPARED_AT);
        var second = fixture.attempts().prepare(
            fixture.grant().grantId(),
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
            MESSAGE,
            PREPARED_AT.plusSeconds(5));

        assertEquals(TradeCounterExecutionAttemptRepository.PreparationState.ALREADY_PREPARED,
            second.state());
        assertEquals(first.attempt().attemptId(), second.attempt().attemptId());
        assertEquals(PREPARED_AT, second.attempt().preparedAt());

        assertThrows(IllegalStateException.class, () -> fixture.attempts().prepare(
            fixture.grant().grantId(),
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
            MESSAGE + " ",
            PREPARED_AT.plusSeconds(10)));
        assertEquals(1, countAttempts(fixture.database()));
    }

    @Test
    void exactPayloadBytesMatterIncludingWhitespace() throws Exception {
        var first = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        var second = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            tempDir.resolve("second.db"));

        var compact = first.attempts().prepare(
            first.grant().grantId(),
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
            MESSAGE,
            PREPARED_AT).attempt();
        var spaced = second.attempts().prepare(
            second.grant().grantId(),
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
            MESSAGE + " ",
            PREPARED_AT).attempt();

        assertNotEquals(compact.payloadSha256(), spaced.payloadSha256());
        assertEquals(MESSAGE, compact.payloadText());
        assertEquals(MESSAGE + " ", spaced.payloadText());
    }

    @Test
    void consumedGrantOrWrongPayloadKindCannotPrepareAttempt() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        var grants = new TradeCounterAuthorizationGrantRepository(fixture.database());
        assertEquals(TradeCounterAuthorizationGrantRepository.ConsumptionResult.CONSUMED,
            grants.consume(
                fixture.grant().grantId(),
                fixture.grant().proposalFingerprint(),
                fixture.grant().action(),
                fixture.grant().destination(),
                PREPARED_AT));

        assertThrows(IllegalStateException.class, () -> fixture.attempts().prepare(
            fixture.grant().grantId(),
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
            MESSAGE,
            PREPARED_AT.plusSeconds(1)));

        var active = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            tempDir.resolve("active.db"));
        assertThrows(IllegalArgumentException.class, () -> active.attempts().prepare(
            active.grant().grantId(),
            TradeCounterExecutionAttemptRepository.PayloadKind.COUNTER_TRADE_REQUEST_JSON,
            "{}",
            PREPARED_AT));
    }

    @Test
    void submitAuthorizationRequiresJsonPayloadKindAndCopiesTrustedLeagueDestination() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE);
        String payload = "{\"league_id\":\"league-1\",\"side_a\":[\"p1\"],\"side_b\":[\"p2\",\"p3\"]}";

        var attempt = fixture.attempts().prepare(
            fixture.grant().grantId(),
            TradeCounterExecutionAttemptRepository.PayloadKind.COUNTER_TRADE_REQUEST_JSON,
            payload,
            PREPARED_AT).attempt();

        assertEquals(TradeCounterAuthorizationPolicy.DestinationType.LEAGUE,
            attempt.destination().type());
        assertEquals("league-1", attempt.destination().id());
        assertEquals("55bd2cf7561689df14f76a19e7234ce43fcffc9431d9c5f58abe858a1d19f1d0",
            attempt.payloadSha256());
    }

    @Test
    void legalStateMachineIsPreparedToInFlightToSucceeded() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        var prepared = fixture.attempts().prepare(
            fixture.grant().grantId(),
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
            MESSAGE,
            PREPARED_AT).attempt();
        Instant inFlightAt = PREPARED_AT.plusSeconds(10);
        Instant terminalAt = PREPARED_AT.plusSeconds(12);

        var inFlight = fixture.attempts().markInFlight(prepared.attemptId(), inFlightAt);
        var succeeded = fixture.attempts().markSucceeded(
            prepared.attemptId(), terminalAt, "platform confirmed success");

        assertEquals(TradeCounterExecutionAttemptRepository.TransitionState.TRANSITIONED,
            inFlight.state());
        assertEquals(TradeCounterExecutionAttemptRepository.State.IN_FLIGHT,
            inFlight.attempt().state());
        assertEquals(inFlightAt, inFlight.attempt().inFlightAt());
        assertEquals(TradeCounterExecutionAttemptRepository.TransitionState.TRANSITIONED,
            succeeded.state());
        assertEquals(TradeCounterExecutionAttemptRepository.State.SUCCEEDED,
            succeeded.attempt().state());
        assertEquals(inFlightAt, succeeded.attempt().inFlightAt());
        assertEquals(terminalAt, succeeded.attempt().terminalAt());
        assertEquals("platform confirmed success", succeeded.attempt().outcomeDetail());
    }

    @Test
    void failedAndUnknownAreTerminalAndCannotBeRetried() throws Exception {
        assertTerminalCannotRetry(
            TradeCounterExecutionAttemptRepository.State.FAILED,
            (repo, id) -> repo.markFailed(id, PREPARED_AT.plusSeconds(20), "definite no-action failure"),
            tempDir.resolve("failed.db"));
        assertTerminalCannotRetry(
            TradeCounterExecutionAttemptRepository.State.UNKNOWN,
            (repo, id) -> repo.markUnknown(id, PREPARED_AT.plusSeconds(20), "timeout; remote outcome unknown"),
            tempDir.resolve("unknown.db"));
    }

    @Test
    void illegalTransitionReturnsInvalidStateAndMissingAttemptReturnsNotFound() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        var prepared = fixture.attempts().prepare(
            fixture.grant().grantId(),
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
            MESSAGE,
            PREPARED_AT).attempt();

        var invalid = fixture.attempts().markSucceeded(
            prepared.attemptId(), PREPARED_AT.plusSeconds(1), "cannot skip IN_FLIGHT");
        var missing = fixture.attempts().markInFlight("missing-attempt", PREPARED_AT.plusSeconds(1));

        assertEquals(TradeCounterExecutionAttemptRepository.TransitionState.INVALID_STATE,
            invalid.state());
        assertEquals(TradeCounterExecutionAttemptRepository.State.PREPARED,
            invalid.attempt().state());
        assertEquals(TradeCounterExecutionAttemptRepository.TransitionState.NOT_FOUND,
            missing.state());
        assertNull(missing.attempt());
    }

    @Test
    void databaseRejectsIntentMutationAndDirectIllegalStateJump() throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE);
        var attempt = fixture.attempts().prepare(
            fixture.grant().grantId(),
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
            MESSAGE,
            PREPARED_AT).attempt();

        try (var connection = fixture.database().openConnection()) {
            assertThrows(SQLException.class, () -> {
                try (var statement = connection.prepareStatement("""
                    UPDATE trade_counter_execution_attempts SET payload_text = ? WHERE attempt_id = ?
                    """)) {
                    statement.setString(1, "different payload");
                    statement.setString(2, attempt.attemptId());
                    statement.executeUpdate();
                }
            });
            assertThrows(SQLException.class, () -> {
                try (var statement = connection.prepareStatement("""
                    UPDATE trade_counter_execution_attempts
                    SET state = 'SUCCEEDED', in_flight_at = ?, terminal_at = ?, outcome_detail = ?, updated_at = ?
                    WHERE attempt_id = ?
                    """)) {
                    String time = PREPARED_AT.plusSeconds(10).toString();
                    statement.setString(1, time);
                    statement.setString(2, time);
                    statement.setString(3, "illegal direct jump");
                    statement.setString(4, time);
                    statement.setString(5, attempt.attemptId());
                    statement.executeUpdate();
                }
            });
        }

        var stored = fixture.attempts().findByAttemptId(attempt.attemptId()).orElseThrow();
        assertEquals(MESSAGE, stored.payloadText());
        assertEquals(TradeCounterExecutionAttemptRepository.State.PREPARED, stored.state());
    }

    private void assertTerminalCannotRetry(
        TradeCounterExecutionAttemptRepository.State expected,
        TerminalTransition terminal,
        Path path) throws Exception {
        var fixture = fixture(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE, path);
        var attempt = fixture.attempts().prepare(
            fixture.grant().grantId(),
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
            MESSAGE,
            PREPARED_AT).attempt();
        assertEquals(TradeCounterExecutionAttemptRepository.TransitionState.TRANSITIONED,
            fixture.attempts().markInFlight(attempt.attemptId(), PREPARED_AT.plusSeconds(10)).state());
        var terminalResult = terminal.apply(fixture.attempts(), attempt.attemptId());

        assertEquals(TradeCounterExecutionAttemptRepository.TransitionState.TRANSITIONED,
            terminalResult.state());
        assertEquals(expected, terminalResult.attempt().state());
        assertEquals(TradeCounterExecutionAttemptRepository.TransitionState.INVALID_STATE,
            fixture.attempts().markInFlight(
                attempt.attemptId(), PREPARED_AT.plusSeconds(30)).state());
        assertEquals(TradeCounterExecutionAttemptRepository.TransitionState.INVALID_STATE,
            fixture.attempts().markSucceeded(
                attempt.attemptId(), PREPARED_AT.plusSeconds(31), "retry forbidden").state());
    }

    private Fixture fixture(TradeCounterAuthorizationPolicy.Action action) throws Exception {
        return fixture(action, tempDir.resolve(action.name().toLowerCase() + ".db"));
    }

    private static Fixture fixture(
        TradeCounterAuthorizationPolicy.Action action,
        Path databasePath) throws Exception {
        Database database = new Database(databasePath);
        var grants = new TradeCounterAuthorizationGrantRepository(database);
        grants.initialize();
        var grant = authorizedGrant(action);
        grants.save(grant);
        var attempts = new TradeCounterExecutionAttemptRepository(database);
        attempts.initialize();
        return new Fixture(database, grant, attempts);
    }

    private static TradeCounterAuthorizationPolicy.AuthorizationGrant authorizedGrant(
        TradeCounterAuthorizationPolicy.Action action) {
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
        var destination = action == TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE
            ? new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22")
            : new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "league-1");
        var request = TradeCounterAuthorizationPolicy.request(identity, action, destination);
        return TradeCounterAuthorizationPolicy.authorize(
            request, request.requiredConfirmation()).grant();
    }

    private static int countAttempts(Database database) throws SQLException {
        try (var connection = database.openConnection();
             var statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT COUNT(*) FROM trade_counter_execution_attempts")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @FunctionalInterface
    private interface TerminalTransition {
        TradeCounterExecutionAttemptRepository.TransitionResult apply(
            TradeCounterExecutionAttemptRepository repository,
            String attemptId) throws Exception;
    }

    private record Fixture(
        Database database,
        TradeCounterAuthorizationPolicy.AuthorizationGrant grant,
        TradeCounterExecutionAttemptRepository attempts) {}
}
