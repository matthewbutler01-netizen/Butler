package io.butler.bet.integration.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
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
 * Immutable resolution journal for the trade-only edge where exact completed Sleeper readback
 * arrives after durable NO_EXTERNAL_ACTION_TAKEN evidence. An unfinalized acknowledgment may be
 * superseded by exact completed readback. A finalized FAILED + CONSUME lifecycle is never rewritten;
 * later completed readback is recorded only as a post-closure discrepancy.
 */
public final class SleeperCounterTradeNoActionResolutionRepository {
    public static final String POLICY_ID =
        "sleeper-counter-trade-no-action-resolution-v1-exact-readback-supersede-or-post-close";

    private final Database database;

    public SleeperCounterTradeNoActionResolutionRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void initialize() throws SQLException {
        new SleeperManualCounterNoActionOutcomeCoordinator(database).initialize();
        new SleeperCounterTradeExpectationSnapshotRepository(database).initialize();
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sleeper_counter_trade_no_action_resolutions (
                    resolution_id TEXT PRIMARY KEY,
                    policy_id TEXT NOT NULL,
                    acknowledgment_id TEXT NOT NULL UNIQUE,
                    no_action_terminal_outcome_id TEXT UNIQUE,
                    claim_id TEXT NOT NULL UNIQUE,
                    attempt_id TEXT NOT NULL UNIQUE,
                    grant_id TEXT NOT NULL UNIQUE,
                    handoff_id TEXT NOT NULL UNIQUE,
                    payload_sha256 TEXT NOT NULL,
                    movement_sha256 TEXT NOT NULL,
                    evidence_policy_id TEXT NOT NULL,
                    reconciliation_service_id TEXT NOT NULL,
                    reconciliation_policy_id TEXT NOT NULL,
                    sleeper_week INTEGER NOT NULL,
                    sleeper_transaction_id TEXT NOT NULL,
                    resolution_type TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    resolved_at TEXT NOT NULL,
                    FOREIGN KEY (acknowledgment_id)
                        REFERENCES sleeper_manual_counter_no_action_acknowledgments(acknowledgment_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (no_action_terminal_outcome_id)
                        REFERENCES sleeper_manual_counter_no_action_terminal_outcomes(outcome_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (claim_id) REFERENCES trade_counter_execution_claims(claim_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (attempt_id) REFERENCES trade_counter_execution_attempts(attempt_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (grant_id) REFERENCES trade_counter_authorization_grants(grant_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (handoff_id) REFERENCES sleeper_manual_counter_handoffs(handoff_id)
                        ON DELETE RESTRICT,
                    CHECK (policy_id = 'sleeper-counter-trade-no-action-resolution-v1-exact-readback-supersede-or-post-close'),
                    CHECK (evidence_policy_id = 'sleeper-counter-trade-reconciliation-outcome-v1-complete-only-success-no-negative-inference'),
                    CHECK (reconciliation_service_id = 'sleeper-counter-trade-snapshot-reconciliation-v1-explicit-week-read-only'),
                    CHECK (reconciliation_policy_id = 'sleeper-trade-reconciliation-v1-exact-assets-rosters-created-after'),
                    CHECK (length(payload_sha256) = 64),
                    CHECK (payload_sha256 NOT GLOB '*[^0-9a-f]*'),
                    CHECK (length(movement_sha256) = 64),
                    CHECK (movement_sha256 NOT GLOB '*[^0-9a-f]*'),
                    CHECK (sleeper_week BETWEEN 1 AND 30),
                    CHECK (length(trim(sleeper_transaction_id)) > 0),
                    CHECK (resolution_type IN ('SUPERSEDED_BY_CONFIRMED_TRADE', 'POST_CLOSURE_EXTERNAL_ACTION')),
                    CHECK (length(trim(reason)) > 0),
                    CHECK ((resolution_type = 'SUPERSEDED_BY_CONFIRMED_TRADE'
                            AND no_action_terminal_outcome_id IS NULL)
                        OR (resolution_type = 'POST_CLOSURE_EXTERNAL_ACTION'
                            AND no_action_terminal_outcome_id IS NOT NULL))
                )
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_sleeper_trade_no_action_resolution_trusted
                BEFORE INSERT ON sleeper_counter_trade_no_action_resolutions
                FOR EACH ROW
                WHEN NOT EXISTS (
                    SELECT 1
                    FROM sleeper_manual_counter_no_action_acknowledgments ack
                    JOIN sleeper_manual_counter_handoffs h ON h.handoff_id = ack.handoff_id
                    JOIN trade_counter_execution_attempts a ON a.attempt_id = ack.attempt_id
                    JOIN trade_counter_authorization_grants g ON g.grant_id = ack.grant_id
                    JOIN sleeper_counter_trade_expectation_snapshots s ON s.claim_id = ack.claim_id
                    LEFT JOIN sleeper_manual_counter_no_action_terminal_outcomes terminal
                      ON terminal.acknowledgment_id = ack.acknowledgment_id
                    WHERE ack.acknowledgment_id = NEW.acknowledgment_id
                      AND ack.claim_id = NEW.claim_id
                      AND ack.attempt_id = NEW.attempt_id
                      AND ack.grant_id = NEW.grant_id
                      AND ack.handoff_id = NEW.handoff_id
                      AND ack.payload_sha256 = NEW.payload_sha256
                      AND ack.action = 'SUBMIT_COUNTER_TRADE'
                      AND ack.destination_type = 'LEAGUE'
                      AND ack.confirmation = 'NO_EXTERNAL_ACTION_TAKEN'
                      AND ack.terminal_eligibility = 'CONFIRMED_NO_ACTION_FAILURE'
                      AND ack.attempt_terminal_state = 'FAILED'
                      AND ack.grant_disposition = 'CONSUME'
                      AND h.claim_id = NEW.claim_id
                      AND h.attempt_id = NEW.attempt_id
                      AND h.grant_id = NEW.grant_id
                      AND h.handoff_id = NEW.handoff_id
                      AND h.payload_sha256 = NEW.payload_sha256
                      AND h.action = 'SUBMIT_COUNTER_TRADE'
                      AND h.destination_type = 'LEAGUE'
                      AND h.payload_kind = 'COUNTER_TRADE_REQUEST_JSON'
                      AND h.reconciliation_mode = 'SLEEPER_TRANSACTION_READBACK'
                      AND s.handoff_id = NEW.handoff_id
                      AND s.movement_sha256 = NEW.movement_sha256
                      AND a.grant_id = NEW.grant_id
                      AND a.payload_sha256 = NEW.payload_sha256
                      AND (
                          (NEW.resolution_type = 'SUPERSEDED_BY_CONFIRMED_TRADE'
                           AND terminal.outcome_id IS NULL
                           AND NEW.no_action_terminal_outcome_id IS NULL
                           AND a.state = 'IN_FLIGHT'
                           AND g.consumed_at IS NULL)
                          OR
                          (NEW.resolution_type = 'POST_CLOSURE_EXTERNAL_ACTION'
                           AND terminal.outcome_id = NEW.no_action_terminal_outcome_id
                           AND terminal.claim_id = NEW.claim_id
                           AND terminal.attempt_id = NEW.attempt_id
                           AND terminal.grant_id = NEW.grant_id
                           AND terminal.handoff_id = NEW.handoff_id
                           AND terminal.terminal_state = 'FAILED'
                           AND terminal.grant_disposition = 'CONSUME'
                           AND a.state = 'FAILED'
                           AND g.consumed_at IS NOT NULL)
                      )
                )
                BEGIN
                    SELECT RAISE(ABORT, 'trade no-action resolution requires exact durable trade evidence and compatible lifecycle state');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_sleeper_trade_no_action_resolution_immutable
                BEFORE UPDATE ON sleeper_counter_trade_no_action_resolutions
                FOR EACH ROW
                BEGIN
                    SELECT RAISE(ABORT, 'trade no-action resolution is immutable');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_sleeper_no_action_terminal_rejects_superseded_trade
                BEFORE INSERT ON sleeper_manual_counter_no_action_terminal_outcomes
                FOR EACH ROW
                WHEN EXISTS (
                    SELECT 1
                    FROM sleeper_counter_trade_no_action_resolutions resolution
                    WHERE resolution.acknowledgment_id = NEW.acknowledgment_id
                      AND resolution.resolution_type = 'SUPERSEDED_BY_CONFIRMED_TRADE'
                )
                BEGIN
                    SELECT RAISE(ABORT, 'superseded trade no-action acknowledgment cannot be finalized as FAILED');
                END
                """);
            if (tableExists(connection, "sleeper_counter_trade_terminal_outcomes")) {
                statement.executeUpdate("""
                    CREATE TRIGGER IF NOT EXISTS trg_sleeper_trade_success_requires_no_action_resolution
                    BEFORE INSERT ON sleeper_counter_trade_terminal_outcomes
                    FOR EACH ROW
                    WHEN EXISTS (
                        SELECT 1
                        FROM sleeper_manual_counter_no_action_acknowledgments ack
                        WHERE ack.claim_id = NEW.claim_id
                          AND ack.attempt_id = NEW.attempt_id
                          AND ack.grant_id = NEW.grant_id
                          AND ack.handoff_id = NEW.handoff_id
                          AND ack.action = 'SUBMIT_COUNTER_TRADE'
                    )
                    AND NOT EXISTS (
                        SELECT 1
                        FROM sleeper_counter_trade_no_action_resolutions resolution
                        JOIN sleeper_manual_counter_no_action_acknowledgments ack
                          ON ack.acknowledgment_id = resolution.acknowledgment_id
                        WHERE resolution.claim_id = NEW.claim_id
                          AND resolution.attempt_id = NEW.attempt_id
                          AND resolution.grant_id = NEW.grant_id
                          AND resolution.handoff_id = NEW.handoff_id
                          AND resolution.movement_sha256 = NEW.movement_sha256
                          AND resolution.sleeper_week = NEW.sleeper_week
                          AND resolution.sleeper_transaction_id = NEW.sleeper_transaction_id
                          AND resolution.resolution_type = 'SUPERSEDED_BY_CONFIRMED_TRADE'
                          AND ack.action = 'SUBMIT_COUNTER_TRADE'
                    )
                    BEGIN
                        SELECT RAISE(ABORT, 'manual trade success conflicts with unresolved no-action acknowledgment');
                    END
                    """);
            }
        }
    }

    ResolutionResult resolve(
        Connection connection,
        SleeperCounterTradeReconciliationOutcomePolicy.Decision decision,
        Instant resolvedAt) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(resolvedAt, "resolvedAt must not be null");
        if (decision.state()
                != SleeperCounterTradeReconciliationOutcomePolicy.State.CONFIRMED_SUCCESS_EVIDENCE
            || decision.terminalOutcomeEligibility()
                != SleeperCounterTradeReconciliationOutcomePolicy.TerminalOutcomeEligibility.CONFIRMED_SUCCESS) {
            return new ResolutionResult(
                ResolutionState.NOT_ELIGIBLE,
                null,
                "Only exact BF-409 confirmed-success evidence may resolve prior trade no-action evidence.");
        }

        StoredAcknowledgment acknowledgment = loadAcknowledgment(connection, decision.claimId()).orElse(null);
        if (acknowledgment == null) {
            return new ResolutionResult(
                ResolutionState.NOT_REQUIRED,
                null,
                "No durable trade no-action acknowledgment exists for this claim.");
        }
        if (!matches(acknowledgment, decision)) {
            return new ResolutionResult(
                ResolutionState.MISMATCH,
                null,
                "Confirmed trade evidence does not match the durable no-action acknowledgment coordinates.");
        }

        TrustedState trusted = loadTrustedState(connection, acknowledgment).orElse(null);
        if (trusted == null || !matches(trusted, decision)) {
            return new ResolutionResult(
                ResolutionState.MISMATCH,
                null,
                "Confirmed trade evidence does not match the trusted handoff/provider snapshot.");
        }

        StoredNoActionOutcome terminal = loadNoActionTerminal(connection, acknowledgment.acknowledgmentId()).orElse(null);
        ResolutionType expectedType = terminal == null
            ? ResolutionType.SUPERSEDED_BY_CONFIRMED_TRADE
            : ResolutionType.POST_CLOSURE_EXTERNAL_ACTION;

        Optional<StoredResolution> existing = findByClaimId(connection, decision.claimId());
        if (existing.isPresent()) {
            if (matches(existing.get(), acknowledgment, terminal, decision, expectedType)) {
                return new ResolutionResult(
                    expectedType == ResolutionType.SUPERSEDED_BY_CONFIRMED_TRADE
                        ? ResolutionState.ALREADY_SUPERSEDED
                        : ResolutionState.POST_CLOSURE_DISCREPANCY_ALREADY_RECORDED,
                    existing.get(),
                    expectedType == ResolutionType.SUPERSEDED_BY_CONFIRMED_TRADE
                        ? "The exact trade no-action acknowledgment was already superseded by this confirmed completed readback."
                        : "This exact post-closure external-action discrepancy was already recorded.");
            }
            return new ResolutionResult(
                ResolutionState.MISMATCH,
                existing.get(),
                "A different durable trade no-action resolution already exists for this claim.");
        }

        if (terminal == null) {
            if (trusted.attemptState() != TradeCounterExecutionAttemptRepository.State.IN_FLIGHT
                || trusted.consumedAt() != null) {
                return new ResolutionResult(
                    ResolutionState.INVALID_STATE,
                    null,
                    "Unfinalized no-action evidence may be superseded only while the attempt is IN_FLIGHT and authorization remains active.");
            }
        } else {
            if (!matches(terminal, acknowledgment)
                || trusted.attemptState() != TradeCounterExecutionAttemptRepository.State.FAILED
                || trusted.consumedAt() == null) {
                return new ResolutionResult(
                    ResolutionState.INVALID_STATE,
                    null,
                    "Post-closure discrepancy requires the exact no-action acknowledgment already finalized as FAILED with authorization closed.");
            }
        }

        StoredResolution stored = new StoredResolution(
            UUID.randomUUID().toString(),
            POLICY_ID,
            acknowledgment.acknowledgmentId(),
            terminal == null ? null : terminal.outcomeId(),
            acknowledgment.claimId(),
            acknowledgment.attemptId(),
            acknowledgment.grantId(),
            acknowledgment.handoffId(),
            acknowledgment.payloadSha256(),
            decision.movementSha256(),
            decision.policyId(),
            decision.reconciliationServiceId(),
            decision.reconciliationPolicyId(),
            decision.week(),
            decision.transactionIds().getFirst(),
            expectedType,
            expectedType == ResolutionType.SUPERSEDED_BY_CONFIRMED_TRADE
                ? "Exact completed Sleeper trade readback superseded the earlier unfinalized no-action acknowledgment; the historical acknowledgment remains immutable."
                : "Exact completed Sleeper trade readback appeared after the no-action lifecycle was already finalized FAILED + CONSUME; historical terminal state remains closed and requires investigation.",
            resolvedAt);
        insert(connection, stored);
        return new ResolutionResult(
            expectedType == ResolutionType.SUPERSEDED_BY_CONFIRMED_TRADE
                ? ResolutionState.SUPERSEDED
                : ResolutionState.POST_CLOSURE_DISCREPANCY_RECORDED,
            stored,
            stored.reason());
    }

    public Optional<StoredResolution> findByClaimId(String claimId) throws SQLException {
        claimId = requireText(claimId, "claimId");
        initialize();
        try (var connection = database.openConnection()) {
            return findByClaimId(connection, claimId);
        }
    }

    static Optional<StoredResolution> findByClaimId(Connection connection, String claimId)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT * FROM sleeper_counter_trade_no_action_resolutions WHERE claim_id = ?
            """)) {
            statement.setString(1, claimId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    private static Optional<StoredAcknowledgment> loadAcknowledgment(
        Connection connection,
        String claimId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT acknowledgment_id, claim_id, attempt_id, grant_id, handoff_id, payload_sha256,
                   action, destination_type, destination_id, confirmation,
                   terminal_eligibility, attempt_terminal_state, grant_disposition
            FROM sleeper_manual_counter_no_action_acknowledgments
            WHERE claim_id = ?
            """)) {
            statement.setString(1, claimId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                var destination = new TradeCounterAuthorizationPolicy.Destination(
                    TradeCounterAuthorizationPolicy.DestinationType.valueOf(rs.getString("destination_type")),
                    rs.getString("destination_id"));
                return Optional.of(new StoredAcknowledgment(
                    rs.getString("acknowledgment_id"),
                    rs.getString("claim_id"),
                    rs.getString("attempt_id"),
                    rs.getString("grant_id"),
                    rs.getString("handoff_id"),
                    rs.getString("payload_sha256"),
                    TradeCounterAuthorizationPolicy.Action.valueOf(rs.getString("action")),
                    destination,
                    rs.getString("confirmation"),
                    rs.getString("terminal_eligibility"),
                    TradeCounterExecutionAttemptRepository.State.valueOf(rs.getString("attempt_terminal_state")),
                    TradeCounterExecutionOutcomePolicy.GrantDisposition.valueOf(rs.getString("grant_disposition"))));
            }
        }
    }

    private static Optional<StoredNoActionOutcome> loadNoActionTerminal(
        Connection connection,
        String acknowledgmentId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT outcome_id, acknowledgment_id, claim_id, attempt_id, grant_id, handoff_id,
                   payload_sha256, terminal_state, grant_disposition
            FROM sleeper_manual_counter_no_action_terminal_outcomes
            WHERE acknowledgment_id = ?
            """)) {
            statement.setString(1, acknowledgmentId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new StoredNoActionOutcome(
                    rs.getString("outcome_id"),
                    rs.getString("acknowledgment_id"),
                    rs.getString("claim_id"),
                    rs.getString("attempt_id"),
                    rs.getString("grant_id"),
                    rs.getString("handoff_id"),
                    rs.getString("payload_sha256"),
                    TradeCounterExecutionAttemptRepository.State.valueOf(rs.getString("terminal_state")),
                    TradeCounterExecutionOutcomePolicy.GrantDisposition.valueOf(rs.getString("grant_disposition"))));
            }
        }
    }

    private static Optional<TrustedState> loadTrustedState(
        Connection connection,
        StoredAcknowledgment acknowledgment) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT a.state, g.consumed_at, s.movement_sha256
            FROM sleeper_manual_counter_handoffs h
            JOIN trade_counter_execution_attempts a ON a.attempt_id = h.attempt_id
            JOIN trade_counter_authorization_grants g ON g.grant_id = h.grant_id
            JOIN sleeper_counter_trade_expectation_snapshots s ON s.claim_id = h.claim_id
            WHERE h.claim_id = ?
              AND h.attempt_id = ?
              AND h.grant_id = ?
              AND h.handoff_id = ?
              AND h.payload_sha256 = ?
              AND h.action = 'SUBMIT_COUNTER_TRADE'
              AND h.destination_type = 'LEAGUE'
              AND h.payload_kind = 'COUNTER_TRADE_REQUEST_JSON'
              AND h.reconciliation_mode = 'SLEEPER_TRANSACTION_READBACK'
              AND s.handoff_id = h.handoff_id
            """)) {
            statement.setString(1, acknowledgment.claimId());
            statement.setString(2, acknowledgment.attemptId());
            statement.setString(3, acknowledgment.grantId());
            statement.setString(4, acknowledgment.handoffId());
            statement.setString(5, acknowledgment.payloadSha256());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String consumed = rs.getString("consumed_at");
                return Optional.of(new TrustedState(
                    acknowledgment.claimId(),
                    acknowledgment.attemptId(),
                    acknowledgment.grantId(),
                    acknowledgment.handoffId(),
                    rs.getString("movement_sha256"),
                    TradeCounterExecutionAttemptRepository.State.valueOf(rs.getString("state")),
                    consumed == null ? null : Instant.parse(consumed)));
            }
        }
    }

    private static void insert(Connection connection, StoredResolution stored) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO sleeper_counter_trade_no_action_resolutions(
                resolution_id, policy_id, acknowledgment_id, no_action_terminal_outcome_id,
                claim_id, attempt_id, grant_id, handoff_id, payload_sha256, movement_sha256,
                evidence_policy_id, reconciliation_service_id, reconciliation_policy_id,
                sleeper_week, sleeper_transaction_id, resolution_type, reason, resolved_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, stored.resolutionId());
            statement.setString(2, stored.policyId());
            statement.setString(3, stored.acknowledgmentId());
            statement.setString(4, stored.noActionTerminalOutcomeId());
            statement.setString(5, stored.claimId());
            statement.setString(6, stored.attemptId());
            statement.setString(7, stored.grantId());
            statement.setString(8, stored.handoffId());
            statement.setString(9, stored.payloadSha256());
            statement.setString(10, stored.movementSha256());
            statement.setString(11, stored.evidencePolicyId());
            statement.setString(12, stored.reconciliationServiceId());
            statement.setString(13, stored.reconciliationPolicyId());
            statement.setInt(14, stored.sleeperWeek());
            statement.setString(15, stored.sleeperTransactionId());
            statement.setString(16, stored.resolutionType().name());
            statement.setString(17, stored.reason());
            statement.setString(18, stored.resolvedAt().toString());
            statement.executeUpdate();
        }
    }

    private static StoredResolution read(ResultSet rs) throws SQLException {
        return new StoredResolution(
            rs.getString("resolution_id"),
            rs.getString("policy_id"),
            rs.getString("acknowledgment_id"),
            rs.getString("no_action_terminal_outcome_id"),
            rs.getString("claim_id"),
            rs.getString("attempt_id"),
            rs.getString("grant_id"),
            rs.getString("handoff_id"),
            rs.getString("payload_sha256"),
            rs.getString("movement_sha256"),
            rs.getString("evidence_policy_id"),
            rs.getString("reconciliation_service_id"),
            rs.getString("reconciliation_policy_id"),
            rs.getInt("sleeper_week"),
            rs.getString("sleeper_transaction_id"),
            ResolutionType.valueOf(rs.getString("resolution_type")),
            rs.getString("reason"),
            Instant.parse(rs.getString("resolved_at")));
    }

    private static boolean matches(
        StoredAcknowledgment acknowledgment,
        SleeperCounterTradeReconciliationOutcomePolicy.Decision decision) {
        return acknowledgment.action() == TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE
            && acknowledgment.destination().type() == TradeCounterAuthorizationPolicy.DestinationType.LEAGUE
            && SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION.equals(acknowledgment.confirmation())
            && acknowledgment.terminalEligibility().equals(
                SleeperManualCounterNoActionAcknowledgmentPolicy.LocalTerminalEligibility.CONFIRMED_NO_ACTION_FAILURE.name())
            && acknowledgment.attemptTerminalState() == TradeCounterExecutionAttemptRepository.State.FAILED
            && acknowledgment.grantDisposition() == TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME
            && acknowledgment.claimId().equals(decision.claimId())
            && acknowledgment.grantId().equals(decision.grantId())
            && acknowledgment.handoffId().equals(decision.handoffId());
    }

    private static boolean matches(
        TrustedState trusted,
        SleeperCounterTradeReconciliationOutcomePolicy.Decision decision) {
        return trusted.claimId().equals(decision.claimId())
            && trusted.grantId().equals(decision.grantId())
            && trusted.handoffId().equals(decision.handoffId())
            && trusted.movementSha256().equals(decision.movementSha256());
    }

    private static boolean matches(
        StoredNoActionOutcome terminal,
        StoredAcknowledgment acknowledgment) {
        return terminal.acknowledgmentId().equals(acknowledgment.acknowledgmentId())
            && terminal.claimId().equals(acknowledgment.claimId())
            && terminal.attemptId().equals(acknowledgment.attemptId())
            && terminal.grantId().equals(acknowledgment.grantId())
            && terminal.handoffId().equals(acknowledgment.handoffId())
            && terminal.payloadSha256().equals(acknowledgment.payloadSha256())
            && terminal.terminalState() == TradeCounterExecutionAttemptRepository.State.FAILED
            && terminal.grantDisposition() == TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME;
    }

    private static boolean matches(
        StoredResolution stored,
        StoredAcknowledgment acknowledgment,
        StoredNoActionOutcome terminal,
        SleeperCounterTradeReconciliationOutcomePolicy.Decision decision,
        ResolutionType expectedType) {
        return stored.acknowledgmentId().equals(acknowledgment.acknowledgmentId())
            && Objects.equals(stored.noActionTerminalOutcomeId(), terminal == null ? null : terminal.outcomeId())
            && stored.claimId().equals(acknowledgment.claimId())
            && stored.attemptId().equals(acknowledgment.attemptId())
            && stored.grantId().equals(decision.grantId())
            && stored.handoffId().equals(decision.handoffId())
            && stored.payloadSha256().equals(acknowledgment.payloadSha256())
            && stored.movementSha256().equals(decision.movementSha256())
            && stored.evidencePolicyId().equals(decision.policyId())
            && stored.reconciliationServiceId().equals(decision.reconciliationServiceId())
            && stored.reconciliationPolicyId().equals(decision.reconciliationPolicyId())
            && stored.sleeperWeek() == decision.week()
            && stored.sleeperTransactionId().equals(decision.transactionIds().getFirst())
            && stored.resolutionType() == expectedType;
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (var rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    public enum ResolutionType {
        SUPERSEDED_BY_CONFIRMED_TRADE,
        POST_CLOSURE_EXTERNAL_ACTION
    }

    public enum ResolutionState {
        NOT_REQUIRED,
        SUPERSEDED,
        ALREADY_SUPERSEDED,
        POST_CLOSURE_DISCREPANCY_RECORDED,
        POST_CLOSURE_DISCREPANCY_ALREADY_RECORDED,
        NOT_ELIGIBLE,
        MISMATCH,
        INVALID_STATE
    }

    public record ResolutionResult(
        ResolutionState state,
        StoredResolution resolution,
        String reason) {
        public ResolutionResult {
            Objects.requireNonNull(state, "state must not be null");
            reason = requireText(reason, "reason");
            boolean carriesResolution = state == ResolutionState.SUPERSEDED
                || state == ResolutionState.ALREADY_SUPERSEDED
                || state == ResolutionState.POST_CLOSURE_DISCREPANCY_RECORDED
                || state == ResolutionState.POST_CLOSURE_DISCREPANCY_ALREADY_RECORDED
                || (state == ResolutionState.MISMATCH && resolution != null);
            if (carriesResolution != (resolution != null)) {
                throw new IllegalArgumentException("resolution payload does not match resolution state");
            }
        }
    }

    public record StoredResolution(
        String resolutionId,
        String policyId,
        String acknowledgmentId,
        String noActionTerminalOutcomeId,
        String claimId,
        String attemptId,
        String grantId,
        String handoffId,
        String payloadSha256,
        String movementSha256,
        String evidencePolicyId,
        String reconciliationServiceId,
        String reconciliationPolicyId,
        int sleeperWeek,
        String sleeperTransactionId,
        ResolutionType resolutionType,
        String reason,
        Instant resolvedAt) {
        public StoredResolution {
            resolutionId = requireText(resolutionId, "resolutionId");
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            acknowledgmentId = requireText(acknowledgmentId, "acknowledgmentId");
            claimId = requireText(claimId, "claimId");
            attemptId = requireText(attemptId, "attemptId");
            grantId = requireText(grantId, "grantId");
            handoffId = requireText(handoffId, "handoffId");
            requireFingerprint(payloadSha256, "payloadSha256");
            requireFingerprint(movementSha256, "movementSha256");
            if (!SleeperCounterTradeReconciliationOutcomePolicy.POLICY_ID.equals(evidencePolicyId)) {
                throw new IllegalArgumentException("unexpected evidencePolicyId");
            }
            if (!SleeperCounterTradeSnapshotReconciliationService.SERVICE_ID.equals(reconciliationServiceId)) {
                throw new IllegalArgumentException("unexpected reconciliationServiceId");
            }
            if (!SleeperTradeReconciliationPolicy.POLICY_ID.equals(reconciliationPolicyId)) {
                throw new IllegalArgumentException("unexpected reconciliationPolicyId");
            }
            if (sleeperWeek < 1 || sleeperWeek > 30) throw new IllegalArgumentException("sleeperWeek must be 1-30");
            sleeperTransactionId = requireText(sleeperTransactionId, "sleeperTransactionId");
            Objects.requireNonNull(resolutionType, "resolutionType must not be null");
            if ((resolutionType == ResolutionType.SUPERSEDED_BY_CONFIRMED_TRADE)
                != (noActionTerminalOutcomeId == null)) {
                throw new IllegalArgumentException("only post-closure discrepancy may reference a no-action terminal outcome");
            }
            if (noActionTerminalOutcomeId != null) {
                noActionTerminalOutcomeId = requireText(noActionTerminalOutcomeId, "noActionTerminalOutcomeId");
            }
            reason = requireText(reason, "reason");
            Objects.requireNonNull(resolvedAt, "resolvedAt must not be null");
        }
    }

    private record StoredAcknowledgment(
        String acknowledgmentId,
        String claimId,
        String attemptId,
        String grantId,
        String handoffId,
        String payloadSha256,
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        String confirmation,
        String terminalEligibility,
        TradeCounterExecutionAttemptRepository.State attemptTerminalState,
        TradeCounterExecutionOutcomePolicy.GrantDisposition grantDisposition) {}

    private record StoredNoActionOutcome(
        String outcomeId,
        String acknowledgmentId,
        String claimId,
        String attemptId,
        String grantId,
        String handoffId,
        String payloadSha256,
        TradeCounterExecutionAttemptRepository.State terminalState,
        TradeCounterExecutionOutcomePolicy.GrantDisposition grantDisposition) {}

    private record TrustedState(
        String claimId,
        String attemptId,
        String grantId,
        String handoffId,
        String movementSha256,
        TradeCounterExecutionAttemptRepository.State attemptState,
        Instant consumedAt) {}

    private static void requireFingerprint(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
