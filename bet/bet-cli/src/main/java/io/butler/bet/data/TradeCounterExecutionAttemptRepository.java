package io.butler.bet.data;

import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable journal for one authorized counter execution attempt.
 * This repository records execution intent and state only; it never performs an external action.
 */
public final class TradeCounterExecutionAttemptRepository {
    public static final String JOURNAL_POLICY_ID =
        "trade-counter-execution-attempt-journal-v1-durable-bound-payload-state-machine";
    public static final String PAYLOAD_HASH_ALGORITHM = "SHA-256";

    private final Database database;

    public TradeCounterExecutionAttemptRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void initialize() throws SQLException {
        new TradeCounterAuthorizationGrantRepository(database).initialize();
        try (var connection = database.openConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS trade_counter_execution_attempts (
                    attempt_id TEXT PRIMARY KEY,
                    journal_policy_id TEXT NOT NULL,
                    grant_id TEXT NOT NULL UNIQUE,
                    authorization_policy_id TEXT NOT NULL,
                    proposal_fingerprint TEXT NOT NULL,
                    action TEXT NOT NULL,
                    destination_type TEXT NOT NULL,
                    destination_id TEXT NOT NULL,
                    payload_kind TEXT NOT NULL,
                    payload_text TEXT NOT NULL,
                    payload_sha256 TEXT NOT NULL,
                    state TEXT NOT NULL,
                    prepared_at TEXT NOT NULL,
                    in_flight_at TEXT,
                    terminal_at TEXT,
                    outcome_detail TEXT,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (grant_id) REFERENCES trade_counter_authorization_grants(grant_id)
                        ON DELETE RESTRICT,
                    CHECK (journal_policy_id = 'trade-counter-execution-attempt-journal-v1-durable-bound-payload-state-machine'),
                    CHECK (authorization_policy_id = 'trade-counter-authorization-v1-explicit-fingerprint-action-destination-once'),
                    CHECK (length(proposal_fingerprint) = 64),
                    CHECK (proposal_fingerprint NOT GLOB '*[^0-9a-f]*'),
                    CHECK (action IN ('SEND_NEGOTIATION_MESSAGE', 'SUBMIT_COUNTER_TRADE')),
                    CHECK (destination_type IN ('MANAGER', 'LEAGUE')),
                    CHECK (payload_kind IN ('NEGOTIATION_MESSAGE_TEXT', 'COUNTER_TRADE_REQUEST_JSON')),
                    CHECK (length(payload_text) > 0),
                    CHECK (length(payload_sha256) = 64),
                    CHECK (payload_sha256 NOT GLOB '*[^0-9a-f]*'),
                    CHECK (state IN ('PREPARED', 'IN_FLIGHT', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
                    CHECK ((action = 'SEND_NEGOTIATION_MESSAGE' AND destination_type = 'MANAGER'
                            AND payload_kind = 'NEGOTIATION_MESSAGE_TEXT')
                        OR (action = 'SUBMIT_COUNTER_TRADE' AND destination_type = 'LEAGUE'
                            AND payload_kind = 'COUNTER_TRADE_REQUEST_JSON')),
                    CHECK ((state = 'PREPARED'
                            AND in_flight_at IS NULL AND terminal_at IS NULL AND outcome_detail IS NULL)
                        OR (state = 'IN_FLIGHT'
                            AND in_flight_at IS NOT NULL AND terminal_at IS NULL AND outcome_detail IS NULL)
                        OR (state IN ('SUCCEEDED', 'FAILED', 'UNKNOWN')
                            AND in_flight_at IS NOT NULL AND terminal_at IS NOT NULL
                            AND outcome_detail IS NOT NULL AND length(trim(outcome_detail)) > 0))
                )
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_trade_counter_execution_attempt_state
                ON trade_counter_execution_attempts(state, updated_at)
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_trade_counter_execution_attempt_active_grant
                BEFORE INSERT ON trade_counter_execution_attempts
                FOR EACH ROW
                WHEN NOT EXISTS (
                    SELECT 1
                    FROM trade_counter_authorization_grants g
                    WHERE g.grant_id = NEW.grant_id
                      AND g.consumed_at IS NULL
                      AND g.policy_id = NEW.authorization_policy_id
                      AND g.proposal_fingerprint = NEW.proposal_fingerprint
                      AND g.action = NEW.action
                      AND g.destination_type = NEW.destination_type
                      AND g.destination_id = NEW.destination_id
                )
                BEGIN
                    SELECT RAISE(ABORT, 'execution attempt requires matching active trusted authorization grant');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_trade_counter_execution_attempt_immutable_intent
                BEFORE UPDATE OF attempt_id, journal_policy_id, grant_id, authorization_policy_id,
                    proposal_fingerprint, action, destination_type, destination_id,
                    payload_kind, payload_text, payload_sha256, prepared_at
                ON trade_counter_execution_attempts
                FOR EACH ROW
                BEGIN
                    SELECT RAISE(ABORT, 'execution attempt intent is immutable after preparation');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_trade_counter_execution_attempt_legal_state
                BEFORE UPDATE OF state ON trade_counter_execution_attempts
                FOR EACH ROW
                WHEN NOT (
                    (OLD.state = 'PREPARED' AND NEW.state = 'IN_FLIGHT')
                    OR (OLD.state = 'IN_FLIGHT' AND NEW.state IN ('SUCCEEDED', 'FAILED', 'UNKNOWN'))
                )
                BEGIN
                    SELECT RAISE(ABORT, 'illegal execution attempt state transition');
                END
                """);
        }
    }

    public PreparationResult prepare(
        String grantId,
        PayloadKind payloadKind,
        String payloadText,
        Instant preparedAt) throws SQLException {
        grantId = requireText(grantId, "grantId");
        Objects.requireNonNull(payloadKind, "payloadKind must not be null");
        payloadText = requirePayload(payloadText);
        Objects.requireNonNull(preparedAt, "preparedAt must not be null");
        initialize();

        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                TrustedCoordinates trusted = loadActiveGrant(connection, grantId);
                requirePayloadKindMatchesAction(payloadKind, trusted.action());
                String payloadHash = sha256(payloadText);

                Optional<ExecutionAttempt> existing = findByGrantId(connection, grantId);
                if (existing.isPresent()) {
                    requireSameIntent(existing.get(), trusted, payloadKind, payloadText, payloadHash);
                    connection.commit();
                    return new PreparationResult(PreparationState.ALREADY_PREPARED, existing.get());
                }

                ExecutionAttempt attempt = new ExecutionAttempt(
                    JOURNAL_POLICY_ID,
                    UUID.randomUUID().toString(),
                    grantId,
                    trusted.authorizationPolicyId(),
                    trusted.proposalFingerprint(),
                    trusted.action(),
                    trusted.destination(),
                    payloadKind,
                    payloadText,
                    payloadHash,
                    State.PREPARED,
                    preparedAt,
                    null,
                    null,
                    null,
                    preparedAt);
                insert(connection, attempt);
                connection.commit();
                return new PreparationResult(PreparationState.PREPARED, attempt);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public Optional<ExecutionAttempt> findByAttemptId(String attemptId) throws SQLException {
        attemptId = requireText(attemptId, "attemptId");
        initialize();
        try (var connection = database.openConnection()) {
            return findByAttemptId(connection, attemptId);
        }
    }

    public Optional<ExecutionAttempt> findByGrantId(String grantId) throws SQLException {
        grantId = requireText(grantId, "grantId");
        initialize();
        try (var connection = database.openConnection()) {
            return findByGrantId(connection, grantId);
        }
    }

    public TransitionResult markInFlight(String attemptId, Instant at) throws SQLException {
        Objects.requireNonNull(at, "at must not be null");
        return transition(attemptId, State.PREPARED, State.IN_FLIGHT, at, null);
    }

    public TransitionResult markSucceeded(String attemptId, Instant at, String outcomeDetail) throws SQLException {
        return transition(attemptId, State.IN_FLIGHT, State.SUCCEEDED, at,
            requireText(outcomeDetail, "outcomeDetail"));
    }

    public TransitionResult markFailed(String attemptId, Instant at, String outcomeDetail) throws SQLException {
        return transition(attemptId, State.IN_FLIGHT, State.FAILED, at,
            requireText(outcomeDetail, "outcomeDetail"));
    }

    public TransitionResult markUnknown(String attemptId, Instant at, String outcomeDetail) throws SQLException {
        return transition(attemptId, State.IN_FLIGHT, State.UNKNOWN, at,
            requireText(outcomeDetail, "outcomeDetail"));
    }

    private TransitionResult transition(
        String attemptId,
        State expected,
        State target,
        Instant at,
        String outcomeDetail) throws SQLException {
        attemptId = requireText(attemptId, "attemptId");
        Objects.requireNonNull(at, "at must not be null");
        initialize();

        String sql;
        if (target == State.IN_FLIGHT) {
            sql = """
                UPDATE trade_counter_execution_attempts
                SET state = ?, in_flight_at = ?, updated_at = ?
                WHERE attempt_id = ? AND state = ?
                """;
        } else {
            sql = """
                UPDATE trade_counter_execution_attempts
                SET state = ?, terminal_at = ?, outcome_detail = ?, updated_at = ?
                WHERE attempt_id = ? AND state = ?
                """;
        }

        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, target.name());
            if (target == State.IN_FLIGHT) {
                statement.setString(2, at.toString());
                statement.setString(3, at.toString());
                statement.setString(4, attemptId);
                statement.setString(5, expected.name());
            } else {
                statement.setString(2, at.toString());
                statement.setString(3, outcomeDetail);
                statement.setString(4, at.toString());
                statement.setString(5, attemptId);
                statement.setString(6, expected.name());
            }
            if (statement.executeUpdate() == 1) {
                return new TransitionResult(TransitionState.TRANSITIONED,
                    findByAttemptId(connection, attemptId).orElseThrow());
            }
        }

        Optional<ExecutionAttempt> stored = findByAttemptId(attemptId);
        if (stored.isEmpty()) return new TransitionResult(TransitionState.NOT_FOUND, null);
        return new TransitionResult(TransitionState.INVALID_STATE, stored.get());
    }

    private static void insert(Connection connection, ExecutionAttempt attempt) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO trade_counter_execution_attempts(
                attempt_id, journal_policy_id, grant_id, authorization_policy_id,
                proposal_fingerprint, action, destination_type, destination_id,
                payload_kind, payload_text, payload_sha256, state, prepared_at,
                in_flight_at, terminal_at, outcome_detail, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?)
            """)) {
            statement.setString(1, attempt.attemptId());
            statement.setString(2, attempt.journalPolicyId());
            statement.setString(3, attempt.grantId());
            statement.setString(4, attempt.authorizationPolicyId());
            statement.setString(5, attempt.proposalFingerprint());
            statement.setString(6, attempt.action().name());
            statement.setString(7, attempt.destination().type().name());
            statement.setString(8, attempt.destination().id());
            statement.setString(9, attempt.payloadKind().name());
            statement.setString(10, attempt.payloadText());
            statement.setString(11, attempt.payloadSha256());
            statement.setString(12, attempt.state().name());
            statement.setString(13, attempt.preparedAt().toString());
            statement.setString(14, attempt.updatedAt().toString());
            statement.executeUpdate();
        }
    }

    private static TrustedCoordinates loadActiveGrant(Connection connection, String grantId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT policy_id, proposal_fingerprint, action, destination_type, destination_id, consumed_at
            FROM trade_counter_authorization_grants
            WHERE grant_id = ?
            """)) {
            statement.setString(1, grantId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException(
                        "execution attempt requires a trusted persisted authorization grant");
                }
                if (rs.getString("consumed_at") != null) {
                    throw new IllegalStateException(
                        "execution attempt cannot be prepared for an already-consumed grant");
                }
                var destination = new TradeCounterAuthorizationPolicy.Destination(
                    TradeCounterAuthorizationPolicy.DestinationType.valueOf(rs.getString("destination_type")),
                    rs.getString("destination_id"));
                return new TrustedCoordinates(
                    rs.getString("policy_id"),
                    rs.getString("proposal_fingerprint"),
                    TradeCounterAuthorizationPolicy.Action.valueOf(rs.getString("action")),
                    destination);
            }
        }
    }

    private static Optional<ExecutionAttempt> findByAttemptId(
        Connection connection,
        String attemptId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT * FROM trade_counter_execution_attempts WHERE attempt_id = ?
            """)) {
            statement.setString(1, attemptId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    private static Optional<ExecutionAttempt> findByGrantId(
        Connection connection,
        String grantId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT * FROM trade_counter_execution_attempts WHERE grant_id = ?
            """)) {
            statement.setString(1, grantId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    private static ExecutionAttempt read(ResultSet rs) throws SQLException {
        String inFlightAt = rs.getString("in_flight_at");
        String terminalAt = rs.getString("terminal_at");
        var destination = new TradeCounterAuthorizationPolicy.Destination(
            TradeCounterAuthorizationPolicy.DestinationType.valueOf(rs.getString("destination_type")),
            rs.getString("destination_id"));
        return new ExecutionAttempt(
            rs.getString("journal_policy_id"),
            rs.getString("attempt_id"),
            rs.getString("grant_id"),
            rs.getString("authorization_policy_id"),
            rs.getString("proposal_fingerprint"),
            TradeCounterAuthorizationPolicy.Action.valueOf(rs.getString("action")),
            destination,
            PayloadKind.valueOf(rs.getString("payload_kind")),
            rs.getString("payload_text"),
            rs.getString("payload_sha256"),
            State.valueOf(rs.getString("state")),
            Instant.parse(rs.getString("prepared_at")),
            inFlightAt == null ? null : Instant.parse(inFlightAt),
            terminalAt == null ? null : Instant.parse(terminalAt),
            rs.getString("outcome_detail"),
            Instant.parse(rs.getString("updated_at")));
    }

    private static void requireSameIntent(
        ExecutionAttempt existing,
        TrustedCoordinates trusted,
        PayloadKind payloadKind,
        String payloadText,
        String payloadHash) {
        if (!existing.authorizationPolicyId().equals(trusted.authorizationPolicyId())
            || !existing.proposalFingerprint().equals(trusted.proposalFingerprint())
            || existing.action() != trusted.action()
            || !existing.destination().equals(trusted.destination())
            || existing.payloadKind() != payloadKind
            || !existing.payloadText().equals(payloadText)
            || !existing.payloadSha256().equals(payloadHash)) {
            throw new IllegalStateException(
                "trusted grant already has a different immutable execution attempt intent");
        }
    }

    private static void requirePayloadKindMatchesAction(
        PayloadKind payloadKind,
        TradeCounterAuthorizationPolicy.Action action) {
        boolean valid = switch (action) {
            case SEND_NEGOTIATION_MESSAGE -> payloadKind == PayloadKind.NEGOTIATION_MESSAGE_TEXT;
            case SUBMIT_COUNTER_TRADE -> payloadKind == PayloadKind.COUNTER_TRADE_REQUEST_JSON;
        };
        if (!valid) {
            throw new IllegalArgumentException("payload kind does not match trusted authorized action");
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance(PAYLOAD_HASH_ALGORITHM)
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(PAYLOAD_HASH_ALGORITHM + " is unavailable", e);
        }
    }

    private static String requirePayload(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("payloadText must not be empty");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static void requireFingerprint(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }

    public enum PayloadKind {
        NEGOTIATION_MESSAGE_TEXT,
        COUNTER_TRADE_REQUEST_JSON
    }

    public enum State {
        PREPARED,
        IN_FLIGHT,
        SUCCEEDED,
        FAILED,
        UNKNOWN
    }

    public enum PreparationState {
        PREPARED,
        ALREADY_PREPARED
    }

    public enum TransitionState {
        TRANSITIONED,
        INVALID_STATE,
        NOT_FOUND
    }

    public record PreparationResult(PreparationState state, ExecutionAttempt attempt) {
        public PreparationResult {
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(attempt, "attempt must not be null");
        }
    }

    public record TransitionResult(TransitionState state, ExecutionAttempt attempt) {
        public TransitionResult {
            Objects.requireNonNull(state, "state must not be null");
            if (state == TransitionState.NOT_FOUND && attempt != null) {
                throw new IllegalArgumentException("NOT_FOUND transition cannot carry attempt");
            }
            if (state != TransitionState.NOT_FOUND && attempt == null) {
                throw new IllegalArgumentException("known transition result requires attempt");
            }
        }
    }

    public record ExecutionAttempt(
        String journalPolicyId,
        String attemptId,
        String grantId,
        String authorizationPolicyId,
        String proposalFingerprint,
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        PayloadKind payloadKind,
        String payloadText,
        String payloadSha256,
        State state,
        Instant preparedAt,
        Instant inFlightAt,
        Instant terminalAt,
        String outcomeDetail,
        Instant updatedAt) {
        public ExecutionAttempt {
            if (!JOURNAL_POLICY_ID.equals(journalPolicyId)) {
                throw new IllegalArgumentException("unexpected journalPolicyId");
            }
            requireText(attemptId, "attemptId");
            requireText(grantId, "grantId");
            if (!TradeCounterAuthorizationPolicy.POLICY_ID.equals(authorizationPolicyId)) {
                throw new IllegalArgumentException("unexpected authorizationPolicyId");
            }
            requireFingerprint(proposalFingerprint, "proposalFingerprint");
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.requireNonNull(payloadKind, "payloadKind must not be null");
            requirePayload(payloadText);
            requireFingerprint(payloadSha256, "payloadSha256");
            if (!sha256(payloadText).equals(payloadSha256)) {
                throw new IllegalArgumentException("payloadSha256 does not match exact payloadText");
            }
            requirePayloadKindMatchesAction(payloadKind, action);
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(preparedAt, "preparedAt must not be null");
            Objects.requireNonNull(updatedAt, "updatedAt must not be null");
            switch (state) {
                case PREPARED -> {
                    if (inFlightAt != null || terminalAt != null || outcomeDetail != null) {
                        throw new IllegalArgumentException("PREPARED cannot carry execution outcome timestamps");
                    }
                }
                case IN_FLIGHT -> {
                    Objects.requireNonNull(inFlightAt, "IN_FLIGHT requires inFlightAt");
                    if (terminalAt != null || outcomeDetail != null) {
                        throw new IllegalArgumentException("IN_FLIGHT cannot carry terminal outcome");
                    }
                }
                case SUCCEEDED, FAILED, UNKNOWN -> {
                    Objects.requireNonNull(inFlightAt, "terminal attempt requires inFlightAt");
                    Objects.requireNonNull(terminalAt, "terminal attempt requires terminalAt");
                    requireText(outcomeDetail, "outcomeDetail");
                }
            }
        }
    }

    private record TrustedCoordinates(
        String authorizationPolicyId,
        String proposalFingerprint,
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination) {}
}
