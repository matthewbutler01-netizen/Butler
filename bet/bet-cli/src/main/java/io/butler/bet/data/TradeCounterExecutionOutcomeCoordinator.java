package io.butler.bet.data;

import io.butler.bet.execution.TradeCounterActionExecutor;
import io.butler.bet.execution.TradeCounterExecutionOutcomePolicy;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durably applies BF-395 execution outcome directives to trusted Butler state.
 * This coordinator mutates local SQLite state only; it never performs an external action.
 */
public final class TradeCounterExecutionOutcomeCoordinator {
    public static final String COORDINATOR_POLICY_ID =
        "trade-counter-execution-outcome-coordinator-v1-atomic-terminal-consume-unknown-lock";

    private final Database database;

    public TradeCounterExecutionOutcomeCoordinator(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void initialize() throws SQLException {
        new TradeCounterExecutionClaimRepository(database).initialize();
        try (var connection = database.openConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS trade_counter_execution_outcomes (
                    outcome_id TEXT PRIMARY KEY,
                    coordinator_policy_id TEXT NOT NULL,
                    outcome_policy_id TEXT NOT NULL,
                    claim_id TEXT NOT NULL UNIQUE,
                    attempt_id TEXT NOT NULL UNIQUE,
                    grant_id TEXT NOT NULL UNIQUE,
                    payload_sha256 TEXT NOT NULL,
                    executor_id TEXT NOT NULL,
                    executor_mode TEXT NOT NULL,
                    executor_state TEXT NOT NULL,
                    outcome_state TEXT NOT NULL,
                    attempt_terminal_state TEXT NOT NULL,
                    grant_disposition TEXT NOT NULL,
                    reconciliation_required INTEGER NOT NULL,
                    executor_detail TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    applied_at TEXT NOT NULL,
                    FOREIGN KEY (claim_id) REFERENCES trade_counter_execution_claims(claim_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (attempt_id) REFERENCES trade_counter_execution_attempts(attempt_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (grant_id) REFERENCES trade_counter_authorization_grants(grant_id)
                        ON DELETE RESTRICT,
                    CHECK (coordinator_policy_id = 'trade-counter-execution-outcome-coordinator-v1-atomic-terminal-consume-unknown-lock'),
                    CHECK (outcome_policy_id = 'trade-counter-execution-outcome-v1-live-terminal-no-retry-unknown-reconcile'),
                    CHECK (length(payload_sha256) = 64),
                    CHECK (payload_sha256 NOT GLOB '*[^0-9a-f]*'),
                    CHECK (executor_mode = 'LIVE'),
                    CHECK (executor_state IN ('DISPATCHED', 'DEFINITE_FAILURE', 'UNKNOWN')),
                    CHECK (outcome_state IN ('CONFIRMED_SUCCESS', 'CONFIRMED_NO_ACTION_FAILURE', 'UNKNOWN_PENDING_RECONCILIATION')),
                    CHECK (attempt_terminal_state IN ('SUCCEEDED', 'FAILED', 'UNKNOWN')),
                    CHECK (grant_disposition IN ('CONSUME', 'RETAIN_ACTIVE')),
                    CHECK (reconciliation_required IN (0, 1)),
                    CHECK (length(trim(executor_id)) > 0),
                    CHECK (length(trim(executor_detail)) > 0),
                    CHECK (length(trim(reason)) > 0),
                    CHECK ((outcome_state = 'CONFIRMED_SUCCESS'
                            AND executor_state = 'DISPATCHED'
                            AND attempt_terminal_state = 'SUCCEEDED'
                            AND grant_disposition = 'CONSUME'
                            AND reconciliation_required = 0)
                        OR (outcome_state = 'CONFIRMED_NO_ACTION_FAILURE'
                            AND executor_state = 'DEFINITE_FAILURE'
                            AND attempt_terminal_state = 'FAILED'
                            AND grant_disposition = 'CONSUME'
                            AND reconciliation_required = 0)
                        OR (outcome_state = 'UNKNOWN_PENDING_RECONCILIATION'
                            AND executor_state = 'UNKNOWN'
                            AND attempt_terminal_state = 'UNKNOWN'
                            AND grant_disposition = 'RETAIN_ACTIVE'
                            AND reconciliation_required = 1))
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS trade_counter_execution_unknown_resolutions (
                    resolution_id TEXT PRIMARY KEY,
                    coordinator_policy_id TEXT NOT NULL,
                    outcome_policy_id TEXT NOT NULL,
                    outcome_id TEXT NOT NULL UNIQUE,
                    claim_id TEXT NOT NULL UNIQUE,
                    attempt_id TEXT NOT NULL UNIQUE,
                    grant_id TEXT NOT NULL UNIQUE,
                    resolution TEXT NOT NULL,
                    grant_disposition TEXT NOT NULL,
                    remote_action_confirmed INTEGER NOT NULL,
                    evidence_detail TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    resolved_at TEXT NOT NULL,
                    FOREIGN KEY (outcome_id) REFERENCES trade_counter_execution_outcomes(outcome_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (claim_id) REFERENCES trade_counter_execution_claims(claim_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (attempt_id) REFERENCES trade_counter_execution_attempts(attempt_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (grant_id) REFERENCES trade_counter_authorization_grants(grant_id)
                        ON DELETE RESTRICT,
                    CHECK (coordinator_policy_id = 'trade-counter-execution-outcome-coordinator-v1-atomic-terminal-consume-unknown-lock'),
                    CHECK (outcome_policy_id = 'trade-counter-execution-outcome-v1-live-terminal-no-retry-unknown-reconcile'),
                    CHECK (resolution IN ('REMOTE_ACTION_CONFIRMED', 'REMOTE_NO_ACTION_CONFIRMED')),
                    CHECK (grant_disposition = 'CONSUME'),
                    CHECK (remote_action_confirmed IN (0, 1)),
                    CHECK (length(trim(evidence_detail)) > 0),
                    CHECK (length(trim(reason)) > 0),
                    CHECK ((resolution = 'REMOTE_ACTION_CONFIRMED' AND remote_action_confirmed = 1)
                        OR (resolution = 'REMOTE_NO_ACTION_CONFIRMED' AND remote_action_confirmed = 0))
                )
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_trade_counter_execution_outcome_matching_in_flight
                BEFORE INSERT ON trade_counter_execution_outcomes
                FOR EACH ROW
                WHEN NOT EXISTS (
                    SELECT 1
                    FROM trade_counter_execution_claims c
                    JOIN trade_counter_execution_attempts a ON a.attempt_id = c.attempt_id
                    JOIN trade_counter_authorization_grants g ON g.grant_id = c.grant_id
                    WHERE c.claim_id = NEW.claim_id
                      AND c.attempt_id = NEW.attempt_id
                      AND c.grant_id = NEW.grant_id
                      AND a.attempt_id = NEW.attempt_id
                      AND a.grant_id = NEW.grant_id
                      AND a.payload_sha256 = NEW.payload_sha256
                      AND a.state = 'IN_FLIGHT'
                      AND g.grant_id = NEW.grant_id
                      AND g.consumed_at IS NULL
                      AND g.proposal_fingerprint = c.proposal_fingerprint
                      AND g.action = c.action
                      AND g.destination_type = c.destination_type
                      AND g.destination_id = c.destination_id
                )
                BEGIN
                    SELECT RAISE(ABORT, 'execution outcome requires matching IN_FLIGHT claim, attempt, payload, and active grant');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_trade_counter_execution_outcome_immutable
                BEFORE UPDATE ON trade_counter_execution_outcomes
                FOR EACH ROW
                BEGIN
                    SELECT RAISE(ABORT, 'execution outcome is immutable');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_trade_counter_execution_unknown_resolution_matching
                BEFORE INSERT ON trade_counter_execution_unknown_resolutions
                FOR EACH ROW
                WHEN NOT EXISTS (
                    SELECT 1
                    FROM trade_counter_execution_outcomes o
                    JOIN trade_counter_execution_attempts a ON a.attempt_id = o.attempt_id
                    JOIN trade_counter_authorization_grants g ON g.grant_id = o.grant_id
                    WHERE o.outcome_id = NEW.outcome_id
                      AND o.claim_id = NEW.claim_id
                      AND o.attempt_id = NEW.attempt_id
                      AND o.grant_id = NEW.grant_id
                      AND o.outcome_state = 'UNKNOWN_PENDING_RECONCILIATION'
                      AND o.attempt_terminal_state = 'UNKNOWN'
                      AND o.grant_disposition = 'RETAIN_ACTIVE'
                      AND o.reconciliation_required = 1
                      AND a.state = 'UNKNOWN'
                      AND g.consumed_at IS NULL
                )
                BEGIN
                    SELECT RAISE(ABORT, 'UNKNOWN resolution requires matching unresolved UNKNOWN outcome and active grant');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_trade_counter_execution_unknown_resolution_immutable
                BEFORE UPDATE ON trade_counter_execution_unknown_resolutions
                FOR EACH ROW
                BEGIN
                    SELECT RAISE(ABORT, 'UNKNOWN resolution is immutable');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_trade_counter_execution_terminal_outcome_required
                BEFORE UPDATE OF state ON trade_counter_execution_attempts
                FOR EACH ROW
                WHEN OLD.state = 'IN_FLIGHT'
                  AND NEW.state IN ('SUCCEEDED', 'FAILED', 'UNKNOWN')
                  AND NOT EXISTS (
                      SELECT 1
                      FROM trade_counter_execution_outcomes o
                      WHERE o.attempt_id = OLD.attempt_id
                        AND o.grant_id = OLD.grant_id
                        AND o.payload_sha256 = OLD.payload_sha256
                        AND o.attempt_terminal_state = NEW.state
                  )
                BEGIN
                    SELECT RAISE(ABORT, 'IN_FLIGHT execution attempt requires durable governed outcome before terminal transition');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_trade_counter_execution_claimed_grant_consumption_guard
                BEFORE UPDATE OF consumed_at ON trade_counter_authorization_grants
                FOR EACH ROW
                WHEN OLD.consumed_at IS NULL
                  AND NEW.consumed_at IS NOT NULL
                  AND EXISTS (
                      SELECT 1 FROM trade_counter_execution_claims c WHERE c.grant_id = OLD.grant_id
                  )
                  AND NOT (
                      EXISTS (
                          SELECT 1
                          FROM trade_counter_execution_outcomes o
                          WHERE o.grant_id = OLD.grant_id
                            AND o.grant_disposition = 'CONSUME'
                            AND o.outcome_state IN ('CONFIRMED_SUCCESS', 'CONFIRMED_NO_ACTION_FAILURE')
                      )
                      OR EXISTS (
                          SELECT 1
                          FROM trade_counter_execution_unknown_resolutions r
                          JOIN trade_counter_execution_outcomes o ON o.outcome_id = r.outcome_id
                          WHERE r.grant_id = OLD.grant_id
                            AND r.grant_disposition = 'CONSUME'
                            AND o.outcome_state = 'UNKNOWN_PENDING_RECONCILIATION'
                      )
                  )
                BEGIN
                    SELECT RAISE(ABORT, 'claimed authorization grant requires governed terminal outcome or UNKNOWN resolution before consumption');
                END
                """);
        }
    }

    public ApplyResult apply(
        TradeCounterExecutionOutcomePolicy.Directive directive,
        Instant appliedAt) throws SQLException {
        Objects.requireNonNull(directive, "directive must not be null");
        Objects.requireNonNull(appliedAt, "appliedAt must not be null");

        if (directive.state() == TradeCounterExecutionOutcomePolicy.OutcomeState.DRY_RUN_NO_MUTATION) {
            return new ApplyResult(
                ApplyState.DRY_RUN_NO_MUTATION,
                null,
                "Dry-run directive intentionally performed no durable mutation.");
        }

        initialize();
        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<StoredOutcome> existing = findOutcomeByAttemptId(connection, directive.attemptId());
                if (existing.isPresent()) {
                    if (matches(existing.get(), directive)) {
                        connection.commit();
                        return new ApplyResult(
                            ApplyState.ALREADY_APPLIED,
                            existing.get(),
                            "The same governed execution outcome was already applied.");
                    }
                    connection.rollback();
                    return new ApplyResult(
                        ApplyState.MISMATCH,
                        existing.get(),
                        "A different durable execution outcome already exists for this attempt.");
                }

                TrustedSnapshot trusted = loadTrustedSnapshot(connection, directive.claimId()).orElse(null);
                if (trusted == null) {
                    connection.rollback();
                    return new ApplyResult(
                        ApplyState.NOT_FOUND,
                        null,
                        "Trusted execution claim was not found.");
                }
                if (!matches(trusted, directive)) {
                    connection.rollback();
                    return new ApplyResult(
                        ApplyState.MISMATCH,
                        null,
                        "Outcome directive does not match trusted persisted claim/attempt/grant state.");
                }
                if (trusted.attemptState() != TradeCounterExecutionAttemptRepository.State.IN_FLIGHT) {
                    connection.rollback();
                    return new ApplyResult(
                        ApplyState.INVALID_STATE,
                        null,
                        "Execution attempt is not IN_FLIGHT.");
                }
                if (trusted.consumed()) {
                    connection.rollback();
                    return new ApplyResult(
                        ApplyState.GRANT_NOT_ACTIVE,
                        null,
                        "Trusted authorization grant is already consumed.");
                }

                StoredOutcome outcome = storedOutcome(directive, appliedAt);
                insertOutcome(connection, outcome);
                if (markTerminal(connection, outcome) != 1) {
                    connection.rollback();
                    return classifyFailedApply(directive);
                }
                if (directive.grantDisposition() == TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME
                    && consumeGrant(connection, trusted, appliedAt) != 1) {
                    connection.rollback();
                    return classifyFailedApply(directive);
                }

                connection.commit();
                return new ApplyResult(
                    ApplyState.APPLIED,
                    outcome,
                    "Governed execution outcome was atomically persisted with required local state changes.");
            } catch (SQLException e) {
                connection.rollback();
                Optional<StoredOutcome> concurrent = findOutcomeByAttemptId(directive.attemptId());
                if (concurrent.isPresent() && matches(concurrent.get(), directive)) {
                    return new ApplyResult(
                        ApplyState.ALREADY_APPLIED,
                        concurrent.get(),
                        "The same governed execution outcome was concurrently applied.");
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

    public ResolutionResult resolveUnknown(
        TradeCounterExecutionOutcomePolicy.UnknownResolutionDirective directive,
        Instant resolvedAt) throws SQLException {
        Objects.requireNonNull(directive, "directive must not be null");
        Objects.requireNonNull(resolvedAt, "resolvedAt must not be null");
        initialize();

        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<StoredUnknownResolution> existing =
                    findResolutionByAttemptId(connection, directive.attemptId());
                if (existing.isPresent()) {
                    if (matches(existing.get(), directive)) {
                        connection.commit();
                        return new ResolutionResult(
                            ResolutionState.ALREADY_RESOLVED,
                            existing.get(),
                            "The same UNKNOWN reconciliation was already applied.");
                    }
                    connection.rollback();
                    return new ResolutionResult(
                        ResolutionState.MISMATCH,
                        existing.get(),
                        "A different UNKNOWN reconciliation already exists for this attempt.");
                }

                UnknownSnapshot unknown = loadUnknownSnapshot(connection, directive.attemptId()).orElse(null);
                if (unknown == null) {
                    connection.rollback();
                    return new ResolutionResult(
                        ResolutionState.NOT_FOUND,
                        null,
                        "Durable UNKNOWN execution outcome was not found.");
                }
                if (!matches(unknown, directive)) {
                    connection.rollback();
                    return new ResolutionResult(
                        ResolutionState.MISMATCH,
                        null,
                        "UNKNOWN reconciliation directive does not match trusted persisted state.");
                }
                if (unknown.attemptState() != TradeCounterExecutionAttemptRepository.State.UNKNOWN) {
                    connection.rollback();
                    return new ResolutionResult(
                        ResolutionState.INVALID_STATE,
                        null,
                        "Historical execution attempt is not UNKNOWN.");
                }
                if (unknown.consumed()) {
                    connection.rollback();
                    return new ResolutionResult(
                        ResolutionState.GRANT_NOT_ACTIVE,
                        null,
                        "Trusted authorization grant is already consumed.");
                }

                StoredUnknownResolution resolution = storedResolution(unknown, directive, resolvedAt);
                insertResolution(connection, resolution);
                if (consumeGrant(connection, unknown.trusted(), resolvedAt) != 1) {
                    connection.rollback();
                    return classifyFailedResolution(directive);
                }

                connection.commit();
                return new ResolutionResult(
                    ResolutionState.RESOLVED,
                    resolution,
                    "UNKNOWN outcome was reconciled and the old one-shot authorization was atomically closed.");
            } catch (SQLException e) {
                connection.rollback();
                Optional<StoredUnknownResolution> concurrent =
                    findResolutionByAttemptId(directive.attemptId());
                if (concurrent.isPresent() && matches(concurrent.get(), directive)) {
                    return new ResolutionResult(
                        ResolutionState.ALREADY_RESOLVED,
                        concurrent.get(),
                        "The same UNKNOWN reconciliation was concurrently applied.");
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

    public Optional<StoredOutcome> findOutcomeByAttemptId(String attemptId) throws SQLException {
        attemptId = requireText(attemptId, "attemptId");
        initialize();
        try (var connection = database.openConnection()) {
            return findOutcomeByAttemptId(connection, attemptId);
        }
    }

    public Optional<StoredUnknownResolution> findResolutionByAttemptId(String attemptId) throws SQLException {
        attemptId = requireText(attemptId, "attemptId");
        initialize();
        try (var connection = database.openConnection()) {
            return findResolutionByAttemptId(connection, attemptId);
        }
    }

    private ApplyResult classifyFailedApply(
        TradeCounterExecutionOutcomePolicy.Directive directive) throws SQLException {
        Optional<StoredOutcome> existing = findOutcomeByAttemptId(directive.attemptId());
        if (existing.isPresent()) {
            return matches(existing.get(), directive)
                ? new ApplyResult(ApplyState.ALREADY_APPLIED, existing.get(),
                    "The same governed execution outcome was already applied.")
                : new ApplyResult(ApplyState.MISMATCH, existing.get(),
                    "A different durable execution outcome already exists for this attempt.");
        }
        try (var connection = database.openConnection()) {
            TrustedSnapshot trusted = loadTrustedSnapshot(connection, directive.claimId()).orElse(null);
            if (trusted == null) return new ApplyResult(ApplyState.NOT_FOUND, null,
                "Trusted execution claim was not found.");
            if (!matches(trusted, directive)) return new ApplyResult(ApplyState.MISMATCH, null,
                "Outcome directive does not match trusted persisted claim/attempt/grant state.");
            if (trusted.consumed()) return new ApplyResult(ApplyState.GRANT_NOT_ACTIVE, null,
                "Trusted authorization grant is already consumed.");
            return new ApplyResult(ApplyState.INVALID_STATE, null,
                "Execution attempt is no longer IN_FLIGHT.");
        }
    }

    private ResolutionResult classifyFailedResolution(
        TradeCounterExecutionOutcomePolicy.UnknownResolutionDirective directive) throws SQLException {
        Optional<StoredUnknownResolution> existing = findResolutionByAttemptId(directive.attemptId());
        if (existing.isPresent()) {
            return matches(existing.get(), directive)
                ? new ResolutionResult(ResolutionState.ALREADY_RESOLVED, existing.get(),
                    "The same UNKNOWN reconciliation was already applied.")
                : new ResolutionResult(ResolutionState.MISMATCH, existing.get(),
                    "A different UNKNOWN reconciliation already exists for this attempt.");
        }
        try (var connection = database.openConnection()) {
            UnknownSnapshot unknown = loadUnknownSnapshot(connection, directive.attemptId()).orElse(null);
            if (unknown == null) return new ResolutionResult(ResolutionState.NOT_FOUND, null,
                "Durable UNKNOWN execution outcome was not found.");
            if (!matches(unknown, directive)) return new ResolutionResult(ResolutionState.MISMATCH, null,
                "UNKNOWN reconciliation directive does not match trusted persisted state.");
            if (unknown.consumed()) return new ResolutionResult(ResolutionState.GRANT_NOT_ACTIVE, null,
                "Trusted authorization grant is already consumed.");
            return new ResolutionResult(ResolutionState.INVALID_STATE, null,
                "Historical execution attempt is no longer in the governed UNKNOWN state.");
        }
    }

    private static StoredOutcome storedOutcome(
        TradeCounterExecutionOutcomePolicy.Directive directive,
        Instant appliedAt) {
        return new StoredOutcome(
            UUID.randomUUID().toString(),
            COORDINATOR_POLICY_ID,
            directive.policyId(),
            directive.claimId(),
            directive.attemptId(),
            directive.grantId(),
            directive.payloadSha256(),
            directive.executorId(),
            directive.executorMode(),
            directive.executorState(),
            directive.state(),
            directive.attemptTerminalState(),
            directive.grantDisposition(),
            directive.reconciliationRequired(),
            directive.executorDetail(),
            directive.reason(),
            appliedAt);
    }

    private static StoredUnknownResolution storedResolution(
        UnknownSnapshot unknown,
        TradeCounterExecutionOutcomePolicy.UnknownResolutionDirective directive,
        Instant resolvedAt) {
        return new StoredUnknownResolution(
            UUID.randomUUID().toString(),
            COORDINATOR_POLICY_ID,
            directive.policyId(),
            unknown.outcomeId(),
            directive.claimId(),
            directive.attemptId(),
            directive.grantId(),
            directive.resolution(),
            directive.grantDisposition(),
            directive.remoteActionConfirmed(),
            directive.evidenceDetail(),
            directive.reason(),
            resolvedAt);
    }

    private static void insertOutcome(Connection connection, StoredOutcome outcome) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO trade_counter_execution_outcomes(
                outcome_id, coordinator_policy_id, outcome_policy_id,
                claim_id, attempt_id, grant_id, payload_sha256,
                executor_id, executor_mode, executor_state, outcome_state,
                attempt_terminal_state, grant_disposition, reconciliation_required,
                executor_detail, reason, applied_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, outcome.outcomeId());
            statement.setString(2, outcome.coordinatorPolicyId());
            statement.setString(3, outcome.outcomePolicyId());
            statement.setString(4, outcome.claimId());
            statement.setString(5, outcome.attemptId());
            statement.setString(6, outcome.grantId());
            statement.setString(7, outcome.payloadSha256());
            statement.setString(8, outcome.executorId());
            statement.setString(9, outcome.executorMode().name());
            statement.setString(10, outcome.executorState().name());
            statement.setString(11, outcome.outcomeState().name());
            statement.setString(12, outcome.attemptTerminalState().name());
            statement.setString(13, outcome.grantDisposition().name());
            statement.setInt(14, outcome.reconciliationRequired() ? 1 : 0);
            statement.setString(15, outcome.executorDetail());
            statement.setString(16, outcome.reason());
            statement.setString(17, outcome.appliedAt().toString());
            statement.executeUpdate();
        }
    }

    private static void insertResolution(
        Connection connection,
        StoredUnknownResolution resolution) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO trade_counter_execution_unknown_resolutions(
                resolution_id, coordinator_policy_id, outcome_policy_id,
                outcome_id, claim_id, attempt_id, grant_id,
                resolution, grant_disposition, remote_action_confirmed,
                evidence_detail, reason, resolved_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, resolution.resolutionId());
            statement.setString(2, resolution.coordinatorPolicyId());
            statement.setString(3, resolution.outcomePolicyId());
            statement.setString(4, resolution.outcomeId());
            statement.setString(5, resolution.claimId());
            statement.setString(6, resolution.attemptId());
            statement.setString(7, resolution.grantId());
            statement.setString(8, resolution.resolution().name());
            statement.setString(9, resolution.grantDisposition().name());
            statement.setInt(10, resolution.remoteActionConfirmed() ? 1 : 0);
            statement.setString(11, resolution.evidenceDetail());
            statement.setString(12, resolution.reason());
            statement.setString(13, resolution.resolvedAt().toString());
            statement.executeUpdate();
        }
    }

    private static int markTerminal(Connection connection, StoredOutcome outcome) throws SQLException {
        try (var statement = connection.prepareStatement("""
            UPDATE trade_counter_execution_attempts
            SET state = ?, terminal_at = ?, outcome_detail = ?, updated_at = ?
            WHERE attempt_id = ?
              AND grant_id = ?
              AND payload_sha256 = ?
              AND state = 'IN_FLIGHT'
              AND EXISTS (
                  SELECT 1 FROM trade_counter_execution_outcomes o
                  WHERE o.outcome_id = ?
                    AND o.attempt_id = trade_counter_execution_attempts.attempt_id
                    AND o.attempt_terminal_state = ?
              )
            """)) {
            statement.setString(1, outcome.attemptTerminalState().name());
            statement.setString(2, outcome.appliedAt().toString());
            statement.setString(3, outcome.executorDetail());
            statement.setString(4, outcome.appliedAt().toString());
            statement.setString(5, outcome.attemptId());
            statement.setString(6, outcome.grantId());
            statement.setString(7, outcome.payloadSha256());
            statement.setString(8, outcome.outcomeId());
            statement.setString(9, outcome.attemptTerminalState().name());
            return statement.executeUpdate();
        }
    }

    private static int consumeGrant(
        Connection connection,
        TrustedSnapshot trusted,
        Instant consumedAt) throws SQLException {
        try (var statement = connection.prepareStatement("""
            UPDATE trade_counter_authorization_grants
            SET consumed_at = ?
            WHERE grant_id = ?
              AND consumed_at IS NULL
              AND max_uses = 1
              AND proposal_fingerprint = ?
              AND action = ?
              AND destination_type = ?
              AND destination_id = ?
            """)) {
            statement.setString(1, consumedAt.toString());
            statement.setString(2, trusted.grantId());
            statement.setString(3, trusted.proposalFingerprint());
            statement.setString(4, trusted.action().name());
            statement.setString(5, trusted.destination().type().name());
            statement.setString(6, trusted.destination().id());
            return statement.executeUpdate();
        }
    }

    private static Optional<TrustedSnapshot> loadTrustedSnapshot(
        Connection connection,
        String claimId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT c.claim_id, c.attempt_id, c.grant_id, c.proposal_fingerprint,
                   c.action, c.destination_type, c.destination_id,
                   a.payload_sha256, a.state, g.consumed_at
            FROM trade_counter_execution_claims c
            JOIN trade_counter_execution_attempts a ON a.attempt_id = c.attempt_id
            JOIN trade_counter_authorization_grants g ON g.grant_id = c.grant_id
            WHERE c.claim_id = ?
            """)) {
            statement.setString(1, claimId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readTrusted(rs));
            }
        }
    }

    private static Optional<UnknownSnapshot> loadUnknownSnapshot(
        Connection connection,
        String attemptId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT o.outcome_id, o.claim_id, o.attempt_id, o.grant_id,
                   o.outcome_state, o.grant_disposition, o.reconciliation_required,
                   c.proposal_fingerprint, c.action, c.destination_type, c.destination_id,
                   a.payload_sha256, a.state, g.consumed_at
            FROM trade_counter_execution_outcomes o
            JOIN trade_counter_execution_claims c ON c.claim_id = o.claim_id
            JOIN trade_counter_execution_attempts a ON a.attempt_id = o.attempt_id
            JOIN trade_counter_authorization_grants g ON g.grant_id = o.grant_id
            WHERE o.attempt_id = ?
            """)) {
            statement.setString(1, attemptId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                TrustedSnapshot trusted = new TrustedSnapshot(
                    rs.getString("claim_id"),
                    rs.getString("attempt_id"),
                    rs.getString("grant_id"),
                    rs.getString("proposal_fingerprint"),
                    TradeCounterAuthorizationPolicy.Action.valueOf(rs.getString("action")),
                    new TradeCounterAuthorizationPolicy.Destination(
                        TradeCounterAuthorizationPolicy.DestinationType.valueOf(rs.getString("destination_type")),
                        rs.getString("destination_id")),
                    rs.getString("payload_sha256"),
                    TradeCounterExecutionAttemptRepository.State.valueOf(rs.getString("state")),
                    rs.getString("consumed_at") != null);
                return Optional.of(new UnknownSnapshot(
                    rs.getString("outcome_id"),
                    TradeCounterExecutionOutcomePolicy.OutcomeState.valueOf(rs.getString("outcome_state")),
                    TradeCounterExecutionOutcomePolicy.GrantDisposition.valueOf(rs.getString("grant_disposition")),
                    rs.getInt("reconciliation_required") == 1,
                    trusted));
            }
        }
    }

    private static Optional<StoredOutcome> findOutcomeByAttemptId(
        Connection connection,
        String attemptId) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT * FROM trade_counter_execution_outcomes WHERE attempt_id = ?")) {
            statement.setString(1, attemptId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(readOutcome(rs)) : Optional.empty();
            }
        }
    }

    private static Optional<StoredUnknownResolution> findResolutionByAttemptId(
        Connection connection,
        String attemptId) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT * FROM trade_counter_execution_unknown_resolutions WHERE attempt_id = ?")) {
            statement.setString(1, attemptId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(readResolution(rs)) : Optional.empty();
            }
        }
    }

    private static StoredOutcome readOutcome(ResultSet rs) throws SQLException {
        return new StoredOutcome(
            rs.getString("outcome_id"),
            rs.getString("coordinator_policy_id"),
            rs.getString("outcome_policy_id"),
            rs.getString("claim_id"),
            rs.getString("attempt_id"),
            rs.getString("grant_id"),
            rs.getString("payload_sha256"),
            rs.getString("executor_id"),
            TradeCounterActionExecutor.Mode.valueOf(rs.getString("executor_mode")),
            TradeCounterActionExecutor.State.valueOf(rs.getString("executor_state")),
            TradeCounterExecutionOutcomePolicy.OutcomeState.valueOf(rs.getString("outcome_state")),
            TradeCounterExecutionAttemptRepository.State.valueOf(rs.getString("attempt_terminal_state")),
            TradeCounterExecutionOutcomePolicy.GrantDisposition.valueOf(rs.getString("grant_disposition")),
            rs.getInt("reconciliation_required") == 1,
            rs.getString("executor_detail"),
            rs.getString("reason"),
            Instant.parse(rs.getString("applied_at")));
    }

    private static StoredUnknownResolution readResolution(ResultSet rs) throws SQLException {
        return new StoredUnknownResolution(
            rs.getString("resolution_id"),
            rs.getString("coordinator_policy_id"),
            rs.getString("outcome_policy_id"),
            rs.getString("outcome_id"),
            rs.getString("claim_id"),
            rs.getString("attempt_id"),
            rs.getString("grant_id"),
            TradeCounterExecutionOutcomePolicy.UnknownResolution.valueOf(rs.getString("resolution")),
            TradeCounterExecutionOutcomePolicy.GrantDisposition.valueOf(rs.getString("grant_disposition")),
            rs.getInt("remote_action_confirmed") == 1,
            rs.getString("evidence_detail"),
            rs.getString("reason"),
            Instant.parse(rs.getString("resolved_at")));
    }

    private static TrustedSnapshot readTrusted(ResultSet rs) throws SQLException {
        return new TrustedSnapshot(
            rs.getString("claim_id"),
            rs.getString("attempt_id"),
            rs.getString("grant_id"),
            rs.getString("proposal_fingerprint"),
            TradeCounterAuthorizationPolicy.Action.valueOf(rs.getString("action")),
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.valueOf(rs.getString("destination_type")),
                rs.getString("destination_id")),
            rs.getString("payload_sha256"),
            TradeCounterExecutionAttemptRepository.State.valueOf(rs.getString("state")),
            rs.getString("consumed_at") != null);
    }

    private static boolean matches(
        TrustedSnapshot trusted,
        TradeCounterExecutionOutcomePolicy.Directive directive) {
        return trusted.claimId().equals(directive.claimId())
            && trusted.attemptId().equals(directive.attemptId())
            && trusted.grantId().equals(directive.grantId())
            && trusted.payloadSha256().equals(directive.payloadSha256());
    }

    private static boolean matches(
        StoredOutcome stored,
        TradeCounterExecutionOutcomePolicy.Directive directive) {
        return stored.outcomePolicyId().equals(directive.policyId())
            && stored.claimId().equals(directive.claimId())
            && stored.attemptId().equals(directive.attemptId())
            && stored.grantId().equals(directive.grantId())
            && stored.payloadSha256().equals(directive.payloadSha256())
            && stored.executorId().equals(directive.executorId())
            && stored.executorMode() == directive.executorMode()
            && stored.executorState() == directive.executorState()
            && stored.outcomeState() == directive.state()
            && stored.attemptTerminalState() == directive.attemptTerminalState()
            && stored.grantDisposition() == directive.grantDisposition()
            && stored.reconciliationRequired() == directive.reconciliationRequired()
            && stored.executorDetail().equals(directive.executorDetail())
            && stored.reason().equals(directive.reason());
    }

    private static boolean matches(
        UnknownSnapshot unknown,
        TradeCounterExecutionOutcomePolicy.UnknownResolutionDirective directive) {
        return unknown.outcomeState() == TradeCounterExecutionOutcomePolicy.OutcomeState.UNKNOWN_PENDING_RECONCILIATION
            && unknown.grantDisposition() == TradeCounterExecutionOutcomePolicy.GrantDisposition.RETAIN_ACTIVE
            && unknown.reconciliationRequired()
            && unknown.trusted().claimId().equals(directive.claimId())
            && unknown.trusted().attemptId().equals(directive.attemptId())
            && unknown.trusted().grantId().equals(directive.grantId());
    }

    private static boolean matches(
        StoredUnknownResolution stored,
        TradeCounterExecutionOutcomePolicy.UnknownResolutionDirective directive) {
        return stored.outcomePolicyId().equals(directive.policyId())
            && stored.claimId().equals(directive.claimId())
            && stored.attemptId().equals(directive.attemptId())
            && stored.grantId().equals(directive.grantId())
            && stored.resolution() == directive.resolution()
            && stored.grantDisposition() == directive.grantDisposition()
            && stored.remoteActionConfirmed() == directive.remoteActionConfirmed()
            && stored.evidenceDetail().equals(directive.evidenceDetail())
            && stored.reason().equals(directive.reason());
    }

    public enum ApplyState {
        APPLIED,
        ALREADY_APPLIED,
        DRY_RUN_NO_MUTATION,
        MISMATCH,
        NOT_FOUND,
        INVALID_STATE,
        GRANT_NOT_ACTIVE
    }

    public enum ResolutionState {
        RESOLVED,
        ALREADY_RESOLVED,
        MISMATCH,
        NOT_FOUND,
        INVALID_STATE,
        GRANT_NOT_ACTIVE
    }

    public record ApplyResult(ApplyState state, StoredOutcome outcome, String reason) {
        public ApplyResult {
            Objects.requireNonNull(state, "state must not be null");
            requireText(reason, "reason");
            if ((state == ApplyState.APPLIED || state == ApplyState.ALREADY_APPLIED
                    || state == ApplyState.MISMATCH) && outcome == null) {
                if (state != ApplyState.MISMATCH) {
                    throw new IllegalArgumentException("applied outcome state requires durable outcome");
                }
            }
            if (state == ApplyState.DRY_RUN_NO_MUTATION && outcome != null) {
                throw new IllegalArgumentException("dry-run result cannot carry durable outcome");
            }
        }
    }

    public record ResolutionResult(
        ResolutionState state,
        StoredUnknownResolution resolution,
        String reason) {
        public ResolutionResult {
            Objects.requireNonNull(state, "state must not be null");
            requireText(reason, "reason");
            if ((state == ResolutionState.RESOLVED || state == ResolutionState.ALREADY_RESOLVED)
                && resolution == null) {
                throw new IllegalArgumentException("resolved state requires durable resolution");
            }
        }
    }

    public record StoredOutcome(
        String outcomeId,
        String coordinatorPolicyId,
        String outcomePolicyId,
        String claimId,
        String attemptId,
        String grantId,
        String payloadSha256,
        String executorId,
        TradeCounterActionExecutor.Mode executorMode,
        TradeCounterActionExecutor.State executorState,
        TradeCounterExecutionOutcomePolicy.OutcomeState outcomeState,
        TradeCounterExecutionAttemptRepository.State attemptTerminalState,
        TradeCounterExecutionOutcomePolicy.GrantDisposition grantDisposition,
        boolean reconciliationRequired,
        String executorDetail,
        String reason,
        Instant appliedAt) {
        public StoredOutcome {
            requireText(outcomeId, "outcomeId");
            if (!COORDINATOR_POLICY_ID.equals(coordinatorPolicyId)) {
                throw new IllegalArgumentException("unexpected coordinatorPolicyId");
            }
            if (!TradeCounterExecutionOutcomePolicy.POLICY_ID.equals(outcomePolicyId)) {
                throw new IllegalArgumentException("unexpected outcomePolicyId");
            }
            requireText(claimId, "claimId");
            requireText(attemptId, "attemptId");
            requireText(grantId, "grantId");
            requireFingerprint(payloadSha256, "payloadSha256");
            requireText(executorId, "executorId");
            if (executorMode != TradeCounterActionExecutor.Mode.LIVE) {
                throw new IllegalArgumentException("durable outcome requires LIVE executor mode");
            }
            Objects.requireNonNull(executorState, "executorState must not be null");
            Objects.requireNonNull(outcomeState, "outcomeState must not be null");
            Objects.requireNonNull(attemptTerminalState, "attemptTerminalState must not be null");
            Objects.requireNonNull(grantDisposition, "grantDisposition must not be null");
            requireText(executorDetail, "executorDetail");
            requireText(reason, "reason");
            Objects.requireNonNull(appliedAt, "appliedAt must not be null");
        }
    }

    public record StoredUnknownResolution(
        String resolutionId,
        String coordinatorPolicyId,
        String outcomePolicyId,
        String outcomeId,
        String claimId,
        String attemptId,
        String grantId,
        TradeCounterExecutionOutcomePolicy.UnknownResolution resolution,
        TradeCounterExecutionOutcomePolicy.GrantDisposition grantDisposition,
        boolean remoteActionConfirmed,
        String evidenceDetail,
        String reason,
        Instant resolvedAt) {
        public StoredUnknownResolution {
            requireText(resolutionId, "resolutionId");
            if (!COORDINATOR_POLICY_ID.equals(coordinatorPolicyId)) {
                throw new IllegalArgumentException("unexpected coordinatorPolicyId");
            }
            if (!TradeCounterExecutionOutcomePolicy.POLICY_ID.equals(outcomePolicyId)) {
                throw new IllegalArgumentException("unexpected outcomePolicyId");
            }
            requireText(outcomeId, "outcomeId");
            requireText(claimId, "claimId");
            requireText(attemptId, "attemptId");
            requireText(grantId, "grantId");
            Objects.requireNonNull(resolution, "resolution must not be null");
            if (grantDisposition != TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME) {
                throw new IllegalArgumentException("UNKNOWN resolution must consume/close old authorization");
            }
            if (remoteActionConfirmed
                != (resolution == TradeCounterExecutionOutcomePolicy.UnknownResolution.REMOTE_ACTION_CONFIRMED)) {
                throw new IllegalArgumentException("remoteActionConfirmed does not match resolution");
            }
            requireText(evidenceDetail, "evidenceDetail");
            requireText(reason, "reason");
            Objects.requireNonNull(resolvedAt, "resolvedAt must not be null");
        }
    }

    private record TrustedSnapshot(
        String claimId,
        String attemptId,
        String grantId,
        String proposalFingerprint,
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        String payloadSha256,
        TradeCounterExecutionAttemptRepository.State attemptState,
        boolean consumed) {
        private TrustedSnapshot {
            requireText(claimId, "claimId");
            requireText(attemptId, "attemptId");
            requireText(grantId, "grantId");
            requireFingerprint(proposalFingerprint, "proposalFingerprint");
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            requireFingerprint(payloadSha256, "payloadSha256");
            Objects.requireNonNull(attemptState, "attemptState must not be null");
        }
    }

    private record UnknownSnapshot(
        String outcomeId,
        TradeCounterExecutionOutcomePolicy.OutcomeState outcomeState,
        TradeCounterExecutionOutcomePolicy.GrantDisposition grantDisposition,
        boolean reconciliationRequired,
        TrustedSnapshot trusted) {
        private UnknownSnapshot {
            requireText(outcomeId, "outcomeId");
            Objects.requireNonNull(outcomeState, "outcomeState must not be null");
            Objects.requireNonNull(grantDisposition, "grantDisposition must not be null");
            Objects.requireNonNull(trusted, "trusted must not be null");
        }

        private TradeCounterExecutionAttemptRepository.State attemptState() {
            return trusted.attemptState();
        }

        private boolean consumed() {
            return trusted.consumed();
        }
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
}