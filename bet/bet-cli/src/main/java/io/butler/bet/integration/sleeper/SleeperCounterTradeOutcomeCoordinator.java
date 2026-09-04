package io.butler.bet.integration.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionOutcomeCoordinator;
import io.butler.bet.data.TradeCounterManualTerminalGuardInstaller;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Atomically finalizes one manual Sleeper counter trade only from BF-409 confirmed-success evidence.
 * This coordinator mutates local Butler state only and never performs an external platform action.
 */
public final class SleeperCounterTradeOutcomeCoordinator {
    public static final String COORDINATOR_POLICY_ID =
        "sleeper-counter-trade-outcome-coordinator-v1-exact-complete-atomic-success-consume";

    private final Database database;

    public SleeperCounterTradeOutcomeCoordinator(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void initialize() throws SQLException {
        new TradeCounterExecutionOutcomeCoordinator(database).initialize();
        new SleeperCounterTradeExpectationSnapshotRepository(database).initialize();
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sleeper_counter_trade_terminal_outcomes (
                    outcome_id TEXT PRIMARY KEY,
                    coordinator_policy_id TEXT NOT NULL,
                    evidence_policy_id TEXT NOT NULL,
                    reconciliation_service_id TEXT NOT NULL,
                    reconciliation_policy_id TEXT NOT NULL,
                    claim_id TEXT NOT NULL UNIQUE,
                    handoff_id TEXT NOT NULL UNIQUE,
                    attempt_id TEXT NOT NULL UNIQUE,
                    grant_id TEXT NOT NULL UNIQUE,
                    movement_sha256 TEXT NOT NULL,
                    sleeper_week INTEGER NOT NULL,
                    sleeper_transaction_id TEXT NOT NULL,
                    terminal_state TEXT NOT NULL,
                    grant_disposition TEXT NOT NULL,
                    evidence_reason TEXT NOT NULL,
                    applied_at TEXT NOT NULL,
                    FOREIGN KEY (claim_id) REFERENCES trade_counter_execution_claims(claim_id) ON DELETE RESTRICT,
                    FOREIGN KEY (handoff_id) REFERENCES sleeper_manual_counter_handoffs(handoff_id) ON DELETE RESTRICT,
                    FOREIGN KEY (attempt_id) REFERENCES trade_counter_execution_attempts(attempt_id) ON DELETE RESTRICT,
                    FOREIGN KEY (grant_id) REFERENCES trade_counter_authorization_grants(grant_id) ON DELETE RESTRICT,
                    CHECK (coordinator_policy_id = 'sleeper-counter-trade-outcome-coordinator-v1-exact-complete-atomic-success-consume'),
                    CHECK (evidence_policy_id = 'sleeper-counter-trade-reconciliation-outcome-v1-complete-only-success-no-negative-inference'),
                    CHECK (reconciliation_service_id = 'sleeper-counter-trade-snapshot-reconciliation-v1-explicit-week-read-only'),
                    CHECK (reconciliation_policy_id = 'sleeper-trade-reconciliation-v1-exact-assets-rosters-created-after'),
                    CHECK (length(movement_sha256) = 64),
                    CHECK (movement_sha256 NOT GLOB '*[^0-9a-f]*'),
                    CHECK (sleeper_week BETWEEN 1 AND 30),
                    CHECK (length(trim(sleeper_transaction_id)) > 0),
                    CHECK (terminal_state = 'SUCCEEDED'),
                    CHECK (grant_disposition = 'CONSUME'),
                    CHECK (length(trim(evidence_reason)) > 0)
                )
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_sleeper_counter_trade_terminal_outcome_trusted
                BEFORE INSERT ON sleeper_counter_trade_terminal_outcomes
                FOR EACH ROW
                WHEN NOT EXISTS (
                    SELECT 1
                    FROM trade_counter_execution_claims c
                    JOIN trade_counter_execution_attempts a ON a.attempt_id = c.attempt_id
                    JOIN trade_counter_authorization_grants g ON g.grant_id = c.grant_id
                    JOIN sleeper_manual_counter_handoffs h ON h.claim_id = c.claim_id
                    JOIN sleeper_counter_trade_expectation_snapshots s ON s.claim_id = c.claim_id
                    WHERE c.claim_id = NEW.claim_id
                      AND c.attempt_id = NEW.attempt_id
                      AND c.grant_id = NEW.grant_id
                      AND a.state = 'IN_FLIGHT'
                      AND a.grant_id = NEW.grant_id
                      AND g.consumed_at IS NULL
                      AND h.handoff_id = NEW.handoff_id
                      AND h.attempt_id = NEW.attempt_id
                      AND h.grant_id = NEW.grant_id
                      AND h.action = 'SUBMIT_COUNTER_TRADE'
                      AND h.reconciliation_mode = 'SLEEPER_TRANSACTION_READBACK'
                      AND s.handoff_id = NEW.handoff_id
                      AND s.movement_sha256 = NEW.movement_sha256
                )
                BEGIN
                    SELECT RAISE(ABORT, 'manual trade terminal outcome requires matching active trusted handoff and provider snapshot');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_sleeper_counter_trade_terminal_outcome_immutable
                BEFORE UPDATE ON sleeper_counter_trade_terminal_outcomes
                FOR EACH ROW
                BEGIN
                    SELECT RAISE(ABORT, 'manual trade terminal outcome is immutable');
                END
                """);
        }
        TradeCounterManualTerminalGuardInstaller.installSleeperTradeSupport(database);
    }

    public ApplyResult apply(
        SleeperCounterTradeReconciliationOutcomePolicy.Decision decision,
        Instant appliedAt) throws SQLException {
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(appliedAt, "appliedAt must not be null");

        if (decision.state()
                != SleeperCounterTradeReconciliationOutcomePolicy.State.CONFIRMED_SUCCESS_EVIDENCE
            || decision.terminalOutcomeEligibility()
                != SleeperCounterTradeReconciliationOutcomePolicy.TerminalOutcomeEligibility.CONFIRMED_SUCCESS) {
            return new ApplyResult(
                ApplyState.NOT_ELIGIBLE,
                null,
                "Only BF-409 CONFIRMED_SUCCESS_EVIDENCE may finalize a manual trade execution.");
        }

        initialize();
        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<StoredOutcome> existing = findByClaimId(connection, decision.claimId());
                if (existing.isPresent()) {
                    if (matches(existing.get(), decision)) {
                        connection.commit();
                        return new ApplyResult(
                            ApplyState.ALREADY_APPLIED,
                            existing.get(),
                            "The exact same completed Sleeper trade evidence was already finalized.");
                    }
                    connection.rollback();
                    return new ApplyResult(
                        ApplyState.MISMATCH,
                        existing.get(),
                        "A different manual-trade terminal outcome already exists for this claim.");
                }

                TrustedSnapshot trusted = loadTrustedSnapshot(connection, decision.claimId()).orElse(null);
                if (trusted == null) {
                    connection.rollback();
                    return new ApplyResult(
                        ApplyState.NOT_FOUND,
                        null,
                        "Trusted manual trade claim/handoff/provider snapshot was not found.");
                }
                if (!matches(trusted, decision)) {
                    connection.rollback();
                    return new ApplyResult(
                        ApplyState.MISMATCH,
                        null,
                        "BF-409 success evidence does not match trusted persisted manual-trade coordinates.");
                }
                if (trusted.attemptState() != TradeCounterExecutionAttemptRepository.State.IN_FLIGHT) {
                    connection.rollback();
                    return new ApplyResult(
                        ApplyState.INVALID_STATE,
                        null,
                        "Manual trade execution attempt is not IN_FLIGHT.");
                }
                if (trusted.consumedAt() != null) {
                    connection.rollback();
                    return new ApplyResult(
                        ApplyState.GRANT_NOT_ACTIVE,
                        null,
                        "Trusted one-shot authorization grant is already consumed.");
                }

                StoredOutcome outcome = new StoredOutcome(
                    UUID.randomUUID().toString(),
                    COORDINATOR_POLICY_ID,
                    decision.policyId(),
                    decision.reconciliationServiceId(),
                    decision.reconciliationPolicyId(),
                    decision.claimId(),
                    decision.handoffId(),
                    trusted.attemptId(),
                    decision.grantId(),
                    decision.movementSha256(),
                    decision.week(),
                    decision.transactionIds().getFirst(),
                    TradeCounterExecutionAttemptRepository.State.SUCCEEDED,
                    "CONSUME",
                    decision.reason(),
                    appliedAt);
                insertOutcome(connection, outcome);

                String detail = "Exact completed Sleeper transaction " + outcome.sleeperTransactionId()
                    + " matched the frozen manual counter handoff in week " + outcome.sleeperWeek() + ".";
                if (markSucceeded(connection, outcome, appliedAt, detail) != 1) {
                    connection.rollback();
                    return classifyFailedApply(decision);
                }
                if (consumeGrant(connection, outcome, appliedAt) != 1) {
                    connection.rollback();
                    return classifyFailedApply(decision);
                }

                connection.commit();
                return new ApplyResult(
                    ApplyState.APPLIED,
                    outcome,
                    "Exact completed Sleeper trade evidence was atomically persisted, the attempt marked SUCCEEDED, and the one-shot authorization consumed.");
            } catch (SQLException e) {
                connection.rollback();
                Optional<StoredOutcome> concurrent = findByClaimId(decision.claimId());
                if (concurrent.isPresent() && matches(concurrent.get(), decision)) {
                    return new ApplyResult(
                        ApplyState.ALREADY_APPLIED,
                        concurrent.get(),
                        "The exact same completed Sleeper trade evidence was concurrently finalized.");
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

    public Optional<StoredOutcome> findByClaimId(String claimId) throws SQLException {
        claimId = requireText(claimId, "claimId");
        initialize();
        try (var connection = database.openConnection()) {
            return findByClaimId(connection, claimId);
        }
    }

    private ApplyResult classifyFailedApply(
        SleeperCounterTradeReconciliationOutcomePolicy.Decision decision) throws SQLException {
        Optional<StoredOutcome> existing = findByClaimId(decision.claimId());
        if (existing.isPresent()) {
            return matches(existing.get(), decision)
                ? new ApplyResult(ApplyState.ALREADY_APPLIED, existing.get(),
                    "The exact same completed Sleeper trade evidence was already finalized.")
                : new ApplyResult(ApplyState.MISMATCH, existing.get(),
                    "A different manual-trade terminal outcome already exists for this claim.");
        }
        try (var connection = database.openConnection()) {
            TrustedSnapshot trusted = loadTrustedSnapshot(connection, decision.claimId()).orElse(null);
            if (trusted == null) return new ApplyResult(ApplyState.NOT_FOUND, null, "Trusted manual trade state was not found.");
            if (!matches(trusted, decision)) return new ApplyResult(ApplyState.MISMATCH, null, "Evidence no longer matches trusted manual trade state.");
            if (trusted.consumedAt() != null) return new ApplyResult(ApplyState.GRANT_NOT_ACTIVE, null, "Trusted authorization is already consumed.");
            return new ApplyResult(ApplyState.INVALID_STATE, null, "Manual trade attempt is no longer IN_FLIGHT.");
        }
    }

    private static Optional<TrustedSnapshot> loadTrustedSnapshot(
        Connection connection,
        String claimId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT c.attempt_id, c.grant_id, a.state, g.consumed_at,
                   h.handoff_id, s.movement_sha256
            FROM trade_counter_execution_claims c
            JOIN trade_counter_execution_attempts a ON a.attempt_id = c.attempt_id
            JOIN trade_counter_authorization_grants g ON g.grant_id = c.grant_id
            JOIN sleeper_manual_counter_handoffs h ON h.claim_id = c.claim_id
            JOIN sleeper_counter_trade_expectation_snapshots s ON s.claim_id = c.claim_id
            WHERE c.claim_id = ?
              AND h.action = 'SUBMIT_COUNTER_TRADE'
              AND h.reconciliation_mode = 'SLEEPER_TRANSACTION_READBACK'
            """)) {
            statement.setString(1, claimId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String consumed = rs.getString("consumed_at");
                return Optional.of(new TrustedSnapshot(
                    claimId,
                    rs.getString("attempt_id"),
                    rs.getString("grant_id"),
                    rs.getString("handoff_id"),
                    rs.getString("movement_sha256"),
                    TradeCounterExecutionAttemptRepository.State.valueOf(rs.getString("state")),
                    consumed == null ? null : Instant.parse(consumed)));
            }
        }
    }

    private static void insertOutcome(Connection connection, StoredOutcome outcome) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO sleeper_counter_trade_terminal_outcomes(
                outcome_id, coordinator_policy_id, evidence_policy_id,
                reconciliation_service_id, reconciliation_policy_id,
                claim_id, handoff_id, attempt_id, grant_id, movement_sha256,
                sleeper_week, sleeper_transaction_id, terminal_state,
                grant_disposition, evidence_reason, applied_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, outcome.outcomeId());
            statement.setString(2, outcome.coordinatorPolicyId());
            statement.setString(3, outcome.evidencePolicyId());
            statement.setString(4, outcome.reconciliationServiceId());
            statement.setString(5, outcome.reconciliationPolicyId());
            statement.setString(6, outcome.claimId());
            statement.setString(7, outcome.handoffId());
            statement.setString(8, outcome.attemptId());
            statement.setString(9, outcome.grantId());
            statement.setString(10, outcome.movementSha256());
            statement.setInt(11, outcome.sleeperWeek());
            statement.setString(12, outcome.sleeperTransactionId());
            statement.setString(13, outcome.terminalState().name());
            statement.setString(14, outcome.grantDisposition());
            statement.setString(15, outcome.evidenceReason());
            statement.setString(16, outcome.appliedAt().toString());
            statement.executeUpdate();
        }
    }

    private static int markSucceeded(
        Connection connection,
        StoredOutcome outcome,
        Instant at,
        String detail) throws SQLException {
        try (var statement = connection.prepareStatement("""
            UPDATE trade_counter_execution_attempts
            SET state = 'SUCCEEDED', terminal_at = ?, outcome_detail = ?, updated_at = ?
            WHERE attempt_id = ? AND grant_id = ? AND state = 'IN_FLIGHT'
            """)) {
            statement.setString(1, at.toString());
            statement.setString(2, detail);
            statement.setString(3, at.toString());
            statement.setString(4, outcome.attemptId());
            statement.setString(5, outcome.grantId());
            return statement.executeUpdate();
        }
    }

    private static int consumeGrant(
        Connection connection,
        StoredOutcome outcome,
        Instant at) throws SQLException {
        try (var statement = connection.prepareStatement("""
            UPDATE trade_counter_authorization_grants
            SET consumed_at = ?
            WHERE grant_id = ? AND consumed_at IS NULL AND max_uses = 1
            """)) {
            statement.setString(1, at.toString());
            statement.setString(2, outcome.grantId());
            return statement.executeUpdate();
        }
    }

    private static Optional<StoredOutcome> findByClaimId(
        Connection connection,
        String claimId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT * FROM sleeper_counter_trade_terminal_outcomes WHERE claim_id = ?
            """)) {
            statement.setString(1, claimId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    private static StoredOutcome read(ResultSet rs) throws SQLException {
        return new StoredOutcome(
            rs.getString("outcome_id"),
            rs.getString("coordinator_policy_id"),
            rs.getString("evidence_policy_id"),
            rs.getString("reconciliation_service_id"),
            rs.getString("reconciliation_policy_id"),
            rs.getString("claim_id"),
            rs.getString("handoff_id"),
            rs.getString("attempt_id"),
            rs.getString("grant_id"),
            rs.getString("movement_sha256"),
            rs.getInt("sleeper_week"),
            rs.getString("sleeper_transaction_id"),
            TradeCounterExecutionAttemptRepository.State.valueOf(rs.getString("terminal_state")),
            rs.getString("grant_disposition"),
            rs.getString("evidence_reason"),
            Instant.parse(rs.getString("applied_at")));
    }

    private static boolean matches(
        StoredOutcome stored,
        SleeperCounterTradeReconciliationOutcomePolicy.Decision decision) {
        return stored.evidencePolicyId().equals(decision.policyId())
            && stored.reconciliationServiceId().equals(decision.reconciliationServiceId())
            && stored.reconciliationPolicyId().equals(decision.reconciliationPolicyId())
            && stored.claimId().equals(decision.claimId())
            && stored.handoffId().equals(decision.handoffId())
            && stored.grantId().equals(decision.grantId())
            && stored.movementSha256().equals(decision.movementSha256())
            && stored.sleeperWeek() == decision.week()
            && stored.sleeperTransactionId().equals(decision.transactionIds().getFirst());
    }

    private static boolean matches(
        TrustedSnapshot trusted,
        SleeperCounterTradeReconciliationOutcomePolicy.Decision decision) {
        return trusted.claimId().equals(decision.claimId())
            && trusted.grantId().equals(decision.grantId())
            && trusted.handoffId().equals(decision.handoffId())
            && trusted.movementSha256().equals(decision.movementSha256());
    }

    public enum ApplyState {
        APPLIED,
        ALREADY_APPLIED,
        NOT_ELIGIBLE,
        NOT_FOUND,
        MISMATCH,
        INVALID_STATE,
        GRANT_NOT_ACTIVE
    }

    public record ApplyResult(ApplyState state, StoredOutcome outcome, String reason) {
        public ApplyResult {
            Objects.requireNonNull(state, "state must not be null");
            reason = requireText(reason, "reason");
            if ((state == ApplyState.APPLIED || state == ApplyState.ALREADY_APPLIED) != (outcome != null)) {
                throw new IllegalArgumentException("only applied states may carry a stored outcome");
            }
        }
    }

    public record StoredOutcome(
        String outcomeId,
        String coordinatorPolicyId,
        String evidencePolicyId,
        String reconciliationServiceId,
        String reconciliationPolicyId,
        String claimId,
        String handoffId,
        String attemptId,
        String grantId,
        String movementSha256,
        int sleeperWeek,
        String sleeperTransactionId,
        TradeCounterExecutionAttemptRepository.State terminalState,
        String grantDisposition,
        String evidenceReason,
        Instant appliedAt) {
        public StoredOutcome {
            outcomeId = requireText(outcomeId, "outcomeId");
            if (!COORDINATOR_POLICY_ID.equals(coordinatorPolicyId)) {
                throw new IllegalArgumentException("unexpected coordinatorPolicyId");
            }
            if (!SleeperCounterTradeReconciliationOutcomePolicy.POLICY_ID.equals(evidencePolicyId)) {
                throw new IllegalArgumentException("unexpected evidencePolicyId");
            }
            if (!SleeperCounterTradeSnapshotReconciliationService.SERVICE_ID.equals(reconciliationServiceId)) {
                throw new IllegalArgumentException("unexpected reconciliationServiceId");
            }
            if (!SleeperTradeReconciliationPolicy.POLICY_ID.equals(reconciliationPolicyId)) {
                throw new IllegalArgumentException("unexpected reconciliationPolicyId");
            }
            claimId = requireText(claimId, "claimId");
            handoffId = requireText(handoffId, "handoffId");
            attemptId = requireText(attemptId, "attemptId");
            grantId = requireText(grantId, "grantId");
            requireFingerprint(movementSha256, "movementSha256");
            if (sleeperWeek < 1 || sleeperWeek > 30) throw new IllegalArgumentException("sleeperWeek must be 1-30");
            sleeperTransactionId = requireText(sleeperTransactionId, "sleeperTransactionId");
            if (terminalState != TradeCounterExecutionAttemptRepository.State.SUCCEEDED) {
                throw new IllegalArgumentException("manual trade terminal outcome must be SUCCEEDED");
            }
            if (!"CONSUME".equals(grantDisposition)) throw new IllegalArgumentException("manual trade success must consume grant");
            evidenceReason = requireText(evidenceReason, "evidenceReason");
            Objects.requireNonNull(appliedAt, "appliedAt must not be null");
        }
    }

    private record TrustedSnapshot(
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
