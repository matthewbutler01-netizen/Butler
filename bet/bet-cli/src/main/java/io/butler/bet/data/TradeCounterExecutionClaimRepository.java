package io.butler.bet.data;

import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import io.butler.bet.intelligence.TradeCounterExecutionReadinessPolicy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Atomically claims one PREPARED execution attempt after governed READY evidence.
 * Claiming records durable intent and moves the attempt to IN_FLIGHT; it never calls an external platform.
 */
public final class TradeCounterExecutionClaimRepository {
    public static final String CLAIM_POLICY_ID =
        "trade-counter-execution-claim-v1-ready-active-prepared-atomic";

    private final Database database;

    public TradeCounterExecutionClaimRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void initialize() throws SQLException {
        new TradeCounterExecutionAttemptRepository(database).initialize();
        try (var connection = database.openConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS trade_counter_execution_claims (
                    claim_id TEXT PRIMARY KEY,
                    claim_policy_id TEXT NOT NULL,
                    attempt_id TEXT NOT NULL UNIQUE,
                    grant_id TEXT NOT NULL UNIQUE,
                    readiness_policy_id TEXT NOT NULL,
                    authorization_policy_id TEXT NOT NULL,
                    proposal_fingerprint TEXT NOT NULL,
                    fresh_fingerprint TEXT NOT NULL,
                    action TEXT NOT NULL,
                    destination_type TEXT NOT NULL,
                    destination_id TEXT NOT NULL,
                    claimed_at TEXT NOT NULL,
                    FOREIGN KEY (attempt_id) REFERENCES trade_counter_execution_attempts(attempt_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (grant_id) REFERENCES trade_counter_authorization_grants(grant_id)
                        ON DELETE RESTRICT,
                    CHECK (claim_policy_id = 'trade-counter-execution-claim-v1-ready-active-prepared-atomic'),
                    CHECK (readiness_policy_id = 'trade-counter-execution-readiness-v1-trusted-grant-fresh-replay-no-consume'),
                    CHECK (authorization_policy_id = 'trade-counter-authorization-v1-explicit-fingerprint-action-destination-once'),
                    CHECK (length(proposal_fingerprint) = 64),
                    CHECK (proposal_fingerprint NOT GLOB '*[^0-9a-f]*'),
                    CHECK (length(fresh_fingerprint) = 64),
                    CHECK (fresh_fingerprint NOT GLOB '*[^0-9a-f]*'),
                    CHECK (proposal_fingerprint = fresh_fingerprint),
                    CHECK (action IN ('SEND_NEGOTIATION_MESSAGE', 'SUBMIT_COUNTER_TRADE')),
                    CHECK (destination_type IN ('MANAGER', 'LEAGUE'))
                )
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_trade_counter_execution_claim_matching_ready_intent
                BEFORE INSERT ON trade_counter_execution_claims
                FOR EACH ROW
                WHEN NOT EXISTS (
                    SELECT 1
                    FROM trade_counter_execution_attempts a
                    JOIN trade_counter_authorization_grants g ON g.grant_id = a.grant_id
                    WHERE a.attempt_id = NEW.attempt_id
                      AND a.state = 'PREPARED'
                      AND a.grant_id = NEW.grant_id
                      AND a.authorization_policy_id = NEW.authorization_policy_id
                      AND a.proposal_fingerprint = NEW.proposal_fingerprint
                      AND a.action = NEW.action
                      AND a.destination_type = NEW.destination_type
                      AND a.destination_id = NEW.destination_id
                      AND g.consumed_at IS NULL
                      AND g.policy_id = NEW.authorization_policy_id
                      AND g.proposal_fingerprint = NEW.proposal_fingerprint
                      AND g.action = NEW.action
                      AND g.destination_type = NEW.destination_type
                      AND g.destination_id = NEW.destination_id
                )
                BEGIN
                    SELECT RAISE(ABORT, 'execution claim requires matching PREPARED attempt and active trusted grant');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_trade_counter_execution_claim_immutable
                BEFORE UPDATE ON trade_counter_execution_claims
                FOR EACH ROW
                BEGIN
                    SELECT RAISE(ABORT, 'execution claim is immutable');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_trade_counter_execution_claim_delete_immutable
                BEFORE DELETE ON trade_counter_execution_claims
                FOR EACH ROW
                BEGIN
                    SELECT RAISE(ABORT, 'execution claim is immutable');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_trade_counter_execution_attempt_claim_required
                BEFORE UPDATE OF state ON trade_counter_execution_attempts
                FOR EACH ROW
                WHEN OLD.state = 'PREPARED'
                  AND NEW.state = 'IN_FLIGHT'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM trade_counter_execution_claims c
                      WHERE c.attempt_id = OLD.attempt_id
                        AND c.grant_id = OLD.grant_id
                        AND c.proposal_fingerprint = OLD.proposal_fingerprint
                        AND c.action = OLD.action
                        AND c.destination_type = OLD.destination_type
                        AND c.destination_id = OLD.destination_id
                  )
                BEGIN
                    SELECT RAISE(ABORT, 'PREPARED execution attempt requires durable READY claim before IN_FLIGHT');
                END
                """);
        }
    }

    public ClaimResult claim(
        String attemptId,
        TradeCounterExecutionReadinessPolicy.Result readiness,
        Instant claimedAt) throws SQLException {
        attemptId = requireText(attemptId, "attemptId");
        Objects.requireNonNull(readiness, "readiness must not be null");
        Objects.requireNonNull(claimedAt, "claimedAt must not be null");

        if (readiness.state() != TradeCounterExecutionReadinessPolicy.State.READY) {
            return result(
                ClaimState.READINESS_NOT_READY,
                null,
                "Execution claim requires BF-391 READY evidence.");
        }
        initialize();

        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<ExecutionClaim> existing = findByAttemptId(connection, attemptId);
                if (existing.isPresent()) {
                    if (!matchesReadiness(existing.get(), readiness)) {
                        connection.rollback();
                        return result(
                            ClaimState.MISMATCH,
                            existing.get(),
                            "Existing execution claim does not match supplied READY evidence.");
                    }
                    connection.commit();
                    return result(
                        ClaimState.ALREADY_CLAIMED,
                        existing.get(),
                        "Execution attempt already has the same durable claim.");
                }

                AttemptSnapshot attempt = findAttempt(connection, attemptId).orElse(null);
                if (attempt == null) {
                    connection.rollback();
                    return result(
                        ClaimState.ATTEMPT_NOT_FOUND,
                        null,
                        "Execution attempt was not found.");
                }
                if (!matchesReadiness(attempt, readiness)) {
                    connection.rollback();
                    return result(
                        ClaimState.MISMATCH,
                        null,
                        "READY evidence does not match the prepared attempt intent.");
                }
                if (attempt.consumed()) {
                    connection.rollback();
                    return result(
                        ClaimState.GRANT_NOT_ACTIVE,
                        null,
                        "Trusted authorization grant is already consumed.");
                }
                if (attempt.state() != TradeCounterExecutionAttemptRepository.State.PREPARED) {
                    connection.rollback();
                    return result(
                        ClaimState.ATTEMPT_NOT_PREPARED,
                        null,
                        "Execution attempt is not in PREPARED state.");
                }

                ExecutionClaim claim = new ExecutionClaim(
                    CLAIM_POLICY_ID,
                    UUID.randomUUID().toString(),
                    attemptId,
                    attempt.grantId(),
                    readiness.policyId(),
                    TradeCounterAuthorizationPolicy.POLICY_ID,
                    readiness.authorizedFingerprint(),
                    readiness.freshFingerprint(),
                    readiness.action(),
                    readiness.destination(),
                    claimedAt);
                insertClaim(connection, claim);

                if (markAttemptInFlight(connection, attemptId, claim, claimedAt) != 1) {
                    connection.rollback();
                    return classifyFailedClaim(attemptId, readiness);
                }

                connection.commit();
                return result(
                    ClaimState.CLAIMED,
                    claim,
                    "READY attempt was atomically claimed and moved to IN_FLIGHT.");
            } catch (SQLException e) {
                connection.rollback();
                Optional<ExecutionClaim> concurrent = findByAttemptId(attemptId);
                if (concurrent.isPresent() && matchesReadiness(concurrent.get(), readiness)) {
                    return result(
                        ClaimState.ALREADY_CLAIMED,
                        concurrent.get(),
                        "Execution attempt was concurrently claimed with the same READY evidence.");
                }
                throw e;
            } catch (RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public Optional<ExecutionClaim> findByAttemptId(String attemptId) throws SQLException {
        attemptId = requireText(attemptId, "attemptId");
        initialize();
        try (var connection = database.openConnection()) {
            return findByAttemptId(connection, attemptId);
        }
    }

    private ClaimResult classifyFailedClaim(
        String attemptId,
        TradeCounterExecutionReadinessPolicy.Result readiness) throws SQLException {
        Optional<ExecutionClaim> concurrent = findByAttemptId(attemptId);
        if (concurrent.isPresent()) {
            return matchesReadiness(concurrent.get(), readiness)
                ? result(
                    ClaimState.ALREADY_CLAIMED,
                    concurrent.get(),
                    "Execution attempt was already claimed with the same READY evidence.")
                : result(
                    ClaimState.MISMATCH,
                    concurrent.get(),
                    "Existing execution claim does not match supplied READY evidence.");
        }
        try (var connection = database.openConnection()) {
            AttemptSnapshot attempt = findAttempt(connection, attemptId).orElse(null);
            if (attempt == null) {
                return result(ClaimState.ATTEMPT_NOT_FOUND, null, "Execution attempt was not found.");
            }
            if (!matchesReadiness(attempt, readiness)) {
                return result(ClaimState.MISMATCH, null,
                    "READY evidence does not match the prepared attempt intent.");
            }
            if (attempt.consumed()) {
                return result(ClaimState.GRANT_NOT_ACTIVE, null,
                    "Trusted authorization grant is already consumed.");
            }
            return result(ClaimState.ATTEMPT_NOT_PREPARED, null,
                "Execution attempt is no longer PREPARED.");
        }
    }

    private static int markAttemptInFlight(
        Connection connection,
        String attemptId,
        ExecutionClaim claim,
        Instant claimedAt) throws SQLException {
        try (var statement = connection.prepareStatement("""
            UPDATE trade_counter_execution_attempts
            SET state = 'IN_FLIGHT', in_flight_at = ?, updated_at = ?
            WHERE attempt_id = ?
              AND state = 'PREPARED'
              AND grant_id = ?
              AND proposal_fingerprint = ?
              AND action = ?
              AND destination_type = ?
              AND destination_id = ?
              AND EXISTS (
                  SELECT 1
                  FROM trade_counter_authorization_grants g
                  WHERE g.grant_id = trade_counter_execution_attempts.grant_id
                    AND g.consumed_at IS NULL
                    AND g.policy_id = trade_counter_execution_attempts.authorization_policy_id
                    AND g.proposal_fingerprint = trade_counter_execution_attempts.proposal_fingerprint
                    AND g.action = trade_counter_execution_attempts.action
                    AND g.destination_type = trade_counter_execution_attempts.destination_type
                    AND g.destination_id = trade_counter_execution_attempts.destination_id
              )
            """)) {
            statement.setString(1, claimedAt.toString());
            statement.setString(2, claimedAt.toString());
            statement.setString(3, attemptId);
            statement.setString(4, claim.grantId());
            statement.setString(5, claim.proposalFingerprint());
            statement.setString(6, claim.action().name());
            statement.setString(7, claim.destination().type().name());
            statement.setString(8, claim.destination().id());
            return statement.executeUpdate();
        }
    }

    private static void insertClaim(Connection connection, ExecutionClaim claim) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO trade_counter_execution_claims(
                claim_id, claim_policy_id, attempt_id, grant_id, readiness_policy_id,
                authorization_policy_id, proposal_fingerprint, fresh_fingerprint,
                action, destination_type, destination_id, claimed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, claim.claimId());
            statement.setString(2, claim.claimPolicyId());
            statement.setString(3, claim.attemptId());
            statement.setString(4, claim.grantId());
            statement.setString(5, claim.readinessPolicyId());
            statement.setString(6, claim.authorizationPolicyId());
            statement.setString(7, claim.proposalFingerprint());
            statement.setString(8, claim.freshFingerprint());
            statement.setString(9, claim.action().name());
            statement.setString(10, claim.destination().type().name());
            statement.setString(11, claim.destination().id());
            statement.setString(12, claim.claimedAt().toString());
            statement.executeUpdate();
        }
    }

    private static Optional<ExecutionClaim> findByAttemptId(
        Connection connection,
        String attemptId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT * FROM trade_counter_execution_claims WHERE attempt_id = ?
            """)) {
            statement.setString(1, attemptId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(readClaim(rs)) : Optional.empty();
            }
        }
    }

    private static Optional<AttemptSnapshot> findAttempt(
        Connection connection,
        String attemptId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT a.grant_id, a.proposal_fingerprint, a.action,
                   a.destination_type, a.destination_id, a.state, g.consumed_at
            FROM trade_counter_execution_attempts a
            JOIN trade_counter_authorization_grants g ON g.grant_id = a.grant_id
            WHERE a.attempt_id = ?
            """)) {
            statement.setString(1, attemptId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new AttemptSnapshot(
                    rs.getString("grant_id"),
                    rs.getString("proposal_fingerprint"),
                    TradeCounterAuthorizationPolicy.Action.valueOf(rs.getString("action")),
                    new TradeCounterAuthorizationPolicy.Destination(
                        TradeCounterAuthorizationPolicy.DestinationType.valueOf(rs.getString("destination_type")),
                        rs.getString("destination_id")),
                    TradeCounterExecutionAttemptRepository.State.valueOf(rs.getString("state")),
                    rs.getString("consumed_at") != null));
            }
        }
    }

    private static ExecutionClaim readClaim(ResultSet rs) throws SQLException {
        return new ExecutionClaim(
            rs.getString("claim_policy_id"),
            rs.getString("claim_id"),
            rs.getString("attempt_id"),
            rs.getString("grant_id"),
            rs.getString("readiness_policy_id"),
            rs.getString("authorization_policy_id"),
            rs.getString("proposal_fingerprint"),
            rs.getString("fresh_fingerprint"),
            TradeCounterAuthorizationPolicy.Action.valueOf(rs.getString("action")),
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.valueOf(rs.getString("destination_type")),
                rs.getString("destination_id")),
            Instant.parse(rs.getString("claimed_at")));
    }

    private static boolean matchesReadiness(
        ExecutionClaim claim,
        TradeCounterExecutionReadinessPolicy.Result readiness) {
        return claim.grantId().equals(readiness.grantId())
            && claim.readinessPolicyId().equals(readiness.policyId())
            && claim.authorizationPolicyId().equals(readiness.authorizationPolicyId())
            && claim.proposalFingerprint().equals(readiness.authorizedFingerprint())
            && claim.freshFingerprint().equals(readiness.freshFingerprint())
            && claim.action() == readiness.action()
            && claim.destination().equals(readiness.destination());
    }

    private static boolean matchesReadiness(
        AttemptSnapshot attempt,
        TradeCounterExecutionReadinessPolicy.Result readiness) {
        return attempt.grantId().equals(readiness.grantId())
            && attempt.proposalFingerprint().equals(readiness.authorizedFingerprint())
            && readiness.authorizedFingerprint().equals(readiness.freshFingerprint())
            && attempt.action() == readiness.action()
            && attempt.destination().equals(readiness.destination());
    }

    private static ClaimResult result(ClaimState state, ExecutionClaim claim, String reason) {
        return new ClaimResult(state, claim, reason);
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

    public enum ClaimState {
        CLAIMED,
        ALREADY_CLAIMED,
        READINESS_NOT_READY,
        ATTEMPT_NOT_FOUND,
        ATTEMPT_NOT_PREPARED,
        GRANT_NOT_ACTIVE,
        MISMATCH
    }

    public record ClaimResult(ClaimState state, ExecutionClaim claim, String reason) {
        public ClaimResult {
            Objects.requireNonNull(state, "state must not be null");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
            if ((state == ClaimState.CLAIMED || state == ClaimState.ALREADY_CLAIMED)
                && claim == null) {
                throw new IllegalArgumentException("claimed state requires durable claim");
            }
            if (state != ClaimState.CLAIMED
                && state != ClaimState.ALREADY_CLAIMED
                && state != ClaimState.MISMATCH
                && claim != null) {
                throw new IllegalArgumentException("non-claim result cannot carry durable claim");
            }
        }
    }

    public record ExecutionClaim(
        String claimPolicyId,
        String claimId,
        String attemptId,
        String grantId,
        String readinessPolicyId,
        String authorizationPolicyId,
        String proposalFingerprint,
        String freshFingerprint,
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        Instant claimedAt) {
        public ExecutionClaim {
            if (!CLAIM_POLICY_ID.equals(claimPolicyId)) {
                throw new IllegalArgumentException("unexpected claimPolicyId");
            }
            requireText(claimId, "claimId");
            requireText(attemptId, "attemptId");
            requireText(grantId, "grantId");
            if (!TradeCounterExecutionReadinessPolicy.POLICY_ID.equals(readinessPolicyId)) {
                throw new IllegalArgumentException("unexpected readinessPolicyId");
            }
            if (!TradeCounterAuthorizationPolicy.POLICY_ID.equals(authorizationPolicyId)) {
                throw new IllegalArgumentException("unexpected authorizationPolicyId");
            }
            requireFingerprint(proposalFingerprint, "proposalFingerprint");
            requireFingerprint(freshFingerprint, "freshFingerprint");
            if (!proposalFingerprint.equals(freshFingerprint)) {
                throw new IllegalArgumentException("execution claim requires matching fresh fingerprint");
            }
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.requireNonNull(claimedAt, "claimedAt must not be null");
        }
    }

    private record AttemptSnapshot(
        String grantId,
        String proposalFingerprint,
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        State state,
        boolean consumed) {}
}
