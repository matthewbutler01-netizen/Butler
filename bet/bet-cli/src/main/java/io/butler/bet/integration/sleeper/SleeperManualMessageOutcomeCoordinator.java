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
 * Atomically finalizes one manually sent Sleeper negotiation message from durable BF-414
 * acknowledgment evidence. This coordinator mutates local Butler state only and never sends a
 * platform message.
 */
public final class SleeperManualMessageOutcomeCoordinator {
    public static final String COORDINATOR_POLICY_ID =
        "sleeper-manual-message-outcome-coordinator-v1-durable-ack-atomic-success-consume";

    private final Database database;

    public SleeperManualMessageOutcomeCoordinator(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void initialize() throws SQLException {
        new TradeCounterExecutionOutcomeCoordinator(database).initialize();
        new SleeperManualMessageAcknowledgmentRepository(database).initialize();
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sleeper_manual_message_terminal_outcomes (
                    outcome_id TEXT PRIMARY KEY,
                    coordinator_policy_id TEXT NOT NULL,
                    acknowledgment_journal_policy_id TEXT NOT NULL,
                    acknowledgment_policy_id TEXT NOT NULL,
                    acknowledgment_id TEXT NOT NULL UNIQUE,
                    claim_id TEXT NOT NULL UNIQUE,
                    attempt_id TEXT NOT NULL UNIQUE,
                    grant_id TEXT NOT NULL UNIQUE,
                    handoff_id TEXT NOT NULL UNIQUE,
                    payload_sha256 TEXT NOT NULL,
                    destination_id TEXT NOT NULL,
                    confirmation TEXT NOT NULL,
                    acknowledged_at TEXT NOT NULL,
                    terminal_state TEXT NOT NULL,
                    grant_disposition TEXT NOT NULL,
                    evidence_reason TEXT NOT NULL,
                    applied_at TEXT NOT NULL,
                    FOREIGN KEY (acknowledgment_id)
                        REFERENCES sleeper_manual_message_acknowledgments(acknowledgment_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (claim_id) REFERENCES trade_counter_execution_claims(claim_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (attempt_id) REFERENCES trade_counter_execution_attempts(attempt_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (grant_id) REFERENCES trade_counter_authorization_grants(grant_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (handoff_id) REFERENCES sleeper_manual_counter_handoffs(handoff_id)
                        ON DELETE RESTRICT,
                    CHECK (coordinator_policy_id = 'sleeper-manual-message-outcome-coordinator-v1-durable-ack-atomic-success-consume'),
                    CHECK (acknowledgment_journal_policy_id = 'sleeper-manual-message-acknowledgment-journal-v1-exact-active-handoff-immutable'),
                    CHECK (acknowledgment_policy_id = 'sleeper-manual-message-acknowledgment-v1-explicit-handoff-payload-confirmation'),
                    CHECK (length(payload_sha256) = 64),
                    CHECK (payload_sha256 NOT GLOB '*[^0-9a-f]*'),
                    CHECK (length(trim(destination_id)) > 0),
                    CHECK (confirmation = 'SENT_EXACT_MESSAGE'),
                    CHECK (terminal_state = 'SUCCEEDED'),
                    CHECK (grant_disposition = 'CONSUME'),
                    CHECK (length(trim(evidence_reason)) > 0)
                )
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_sleeper_manual_message_terminal_outcome_trusted
                BEFORE INSERT ON sleeper_manual_message_terminal_outcomes
                FOR EACH ROW
                WHEN NOT EXISTS (
                    SELECT 1
                    FROM sleeper_manual_message_acknowledgments ack
                    JOIN sleeper_manual_counter_handoffs h ON h.handoff_id = ack.handoff_id
                    JOIN trade_counter_execution_attempts a ON a.attempt_id = ack.attempt_id
                    JOIN trade_counter_authorization_grants g ON g.grant_id = ack.grant_id
                    WHERE ack.acknowledgment_id = NEW.acknowledgment_id
                      AND ack.claim_id = NEW.claim_id
                      AND ack.attempt_id = NEW.attempt_id
                      AND ack.grant_id = NEW.grant_id
                      AND ack.handoff_id = NEW.handoff_id
                      AND ack.payload_sha256 = NEW.payload_sha256
                      AND ack.destination_id = NEW.destination_id
                      AND ack.confirmation = NEW.confirmation
                      AND ack.completion_eligibility = 'MANUAL_MESSAGE_SUCCESS'
                      AND ack.acknowledged_at = NEW.acknowledged_at
                      AND h.claim_id = NEW.claim_id
                      AND h.attempt_id = NEW.attempt_id
                      AND h.grant_id = NEW.grant_id
                      AND h.payload_sha256 = NEW.payload_sha256
                      AND h.destination_type = 'MANAGER'
                      AND h.destination_id = NEW.destination_id
                      AND h.action = 'SEND_NEGOTIATION_MESSAGE'
                      AND h.payload_kind = 'NEGOTIATION_MESSAGE_TEXT'
                      AND h.reconciliation_mode = 'NO_OFFICIAL_READBACK'
                      AND a.state = 'IN_FLIGHT'
                      AND a.grant_id = NEW.grant_id
                      AND a.payload_sha256 = NEW.payload_sha256
                      AND g.consumed_at IS NULL
                )
                BEGIN
                    SELECT RAISE(ABORT, 'manual message terminal outcome requires matching durable acknowledgment and active trusted handoff');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_sleeper_manual_message_terminal_outcome_immutable
                BEFORE UPDATE ON sleeper_manual_message_terminal_outcomes
                FOR EACH ROW
                BEGIN
                    SELECT RAISE(ABORT, 'manual message terminal outcome is immutable');
                END
                """);
        }
        TradeCounterManualTerminalGuardInstaller.installSleeperMessageSupport(database);
    }

    public ApplyResult apply(String claimId, Instant appliedAt) throws SQLException {
        claimId = requireText(claimId, "claimId");
        Objects.requireNonNull(appliedAt, "appliedAt must not be null");
        initialize();

        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<StoredOutcome> existing = findByClaimId(connection, claimId);
                if (existing.isPresent()) {
                    connection.commit();
                    return new ApplyResult(
                        ApplyState.ALREADY_APPLIED,
                        existing.get(),
                        "The durable manual-message acknowledgment was already finalized.");
                }

                StoredAcknowledgment acknowledgment = loadAcknowledgment(connection, claimId).orElse(null);
                if (acknowledgment == null) {
                    connection.rollback();
                    return new ApplyResult(
                        ApplyState.NOT_FOUND,
                        null,
                        "Durable BF-414 manual-message acknowledgment was not found.");
                }
                TrustedState trusted = loadTrustedState(connection, acknowledgment).orElse(null);
                if (trusted == null) {
                    connection.rollback();
                    return new ApplyResult(
                        ApplyState.MISMATCH,
                        null,
                        "Durable acknowledgment no longer matches the trusted manual-message handoff.");
                }
                if (trusted.attemptState() != TradeCounterExecutionAttemptRepository.State.IN_FLIGHT) {
                    connection.rollback();
                    return new ApplyResult(
                        ApplyState.INVALID_STATE,
                        null,
                        "Manual-message execution attempt is not IN_FLIGHT.");
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
                    acknowledgment.journalPolicyId(),
                    acknowledgment.acknowledgmentPolicyId(),
                    acknowledgment.acknowledgmentId(),
                    acknowledgment.claimId(),
                    acknowledgment.attemptId(),
                    acknowledgment.grantId(),
                    acknowledgment.handoffId(),
                    acknowledgment.payloadSha256(),
                    acknowledgment.destinationId(),
                    acknowledgment.confirmation(),
                    acknowledgment.acknowledgedAt(),
                    TradeCounterExecutionAttemptRepository.State.SUCCEEDED,
                    "CONSUME",
                    acknowledgment.evidenceReason(),
                    appliedAt);
                insertOutcome(connection, outcome);

                String detail = "Explicit durable acknowledgment confirmed the exact manual negotiation message was sent to manager "
                    + outcome.destinationId() + ".";
                if (markSucceeded(connection, outcome, appliedAt, detail) != 1) {
                    connection.rollback();
                    return classifyFailedApply(claimId);
                }
                if (consumeGrant(connection, outcome, appliedAt) != 1) {
                    connection.rollback();
                    return classifyFailedApply(claimId);
                }

                connection.commit();
                return new ApplyResult(
                    ApplyState.APPLIED,
                    outcome,
                    "Durable manual-message acknowledgment was atomically finalized as SUCCEEDED and the one-shot authorization consumed.");
            } catch (SQLException e) {
                connection.rollback();
                Optional<StoredOutcome> concurrent = findByClaimId(claimId);
                if (concurrent.isPresent()) {
                    return new ApplyResult(
                        ApplyState.ALREADY_APPLIED,
                        concurrent.get(),
                        "The durable manual-message acknowledgment was concurrently finalized.");
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

    private ApplyResult classifyFailedApply(String claimId) throws SQLException {
        Optional<StoredOutcome> existing = findByClaimId(claimId);
        if (existing.isPresent()) {
            return new ApplyResult(
                ApplyState.ALREADY_APPLIED,
                existing.get(),
                "The durable manual-message acknowledgment was already finalized.");
        }
        try (var connection = database.openConnection()) {
            StoredAcknowledgment acknowledgment = loadAcknowledgment(connection, claimId).orElse(null);
            if (acknowledgment == null) {
                return new ApplyResult(ApplyState.NOT_FOUND, null,
                    "Durable BF-414 manual-message acknowledgment was not found.");
            }
            TrustedState trusted = loadTrustedState(connection, acknowledgment).orElse(null);
            if (trusted == null) {
                return new ApplyResult(ApplyState.MISMATCH, null,
                    "Durable acknowledgment no longer matches trusted manual-message state.");
            }
            if (trusted.consumedAt() != null) {
                return new ApplyResult(ApplyState.GRANT_NOT_ACTIVE, null,
                    "Trusted one-shot authorization grant is already consumed.");
            }
            return new ApplyResult(ApplyState.INVALID_STATE, null,
                "Manual-message execution attempt is no longer IN_FLIGHT.");
        }
    }

    private static Optional<StoredAcknowledgment> loadAcknowledgment(
        Connection connection,
        String claimId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT * FROM sleeper_manual_message_acknowledgments WHERE claim_id = ?
            """)) {
            statement.setString(1, claimId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new StoredAcknowledgment(
                    rs.getString("acknowledgment_id"),
                    rs.getString("journal_policy_id"),
                    rs.getString("acknowledgment_policy_id"),
                    rs.getString("claim_id"),
                    rs.getString("attempt_id"),
                    rs.getString("grant_id"),
                    rs.getString("handoff_id"),
                    rs.getString("payload_sha256"),
                    rs.getString("destination_id"),
                    rs.getString("confirmation"),
                    rs.getString("completion_eligibility"),
                    Instant.parse(rs.getString("acknowledged_at")),
                    rs.getString("evidence_reason")));
            }
        }
    }

    private static Optional<TrustedState> loadTrustedState(
        Connection connection,
        StoredAcknowledgment acknowledgment) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT a.state, g.consumed_at
            FROM sleeper_manual_counter_handoffs h
            JOIN trade_counter_execution_attempts a ON a.attempt_id = h.attempt_id
            JOIN trade_counter_authorization_grants g ON g.grant_id = h.grant_id
            WHERE h.claim_id = ?
              AND h.attempt_id = ?
              AND h.grant_id = ?
              AND h.handoff_id = ?
              AND h.payload_sha256 = ?
              AND h.destination_type = 'MANAGER'
              AND h.destination_id = ?
              AND h.action = 'SEND_NEGOTIATION_MESSAGE'
              AND h.payload_kind = 'NEGOTIATION_MESSAGE_TEXT'
              AND h.reconciliation_mode = 'NO_OFFICIAL_READBACK'
              AND a.grant_id = h.grant_id
              AND a.payload_sha256 = h.payload_sha256
            """)) {
            statement.setString(1, acknowledgment.claimId());
            statement.setString(2, acknowledgment.attemptId());
            statement.setString(3, acknowledgment.grantId());
            statement.setString(4, acknowledgment.handoffId());
            statement.setString(5, acknowledgment.payloadSha256());
            statement.setString(6, acknowledgment.destinationId());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String consumed = rs.getString("consumed_at");
                return Optional.of(new TrustedState(
                    TradeCounterExecutionAttemptRepository.State.valueOf(rs.getString("state")),
                    consumed == null ? null : Instant.parse(consumed)));
            }
        }
    }

    private static void insertOutcome(Connection connection, StoredOutcome outcome) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO sleeper_manual_message_terminal_outcomes(
                outcome_id, coordinator_policy_id, acknowledgment_journal_policy_id,
                acknowledgment_policy_id, acknowledgment_id, claim_id, attempt_id, grant_id,
                handoff_id, payload_sha256, destination_id, confirmation, acknowledged_at,
                terminal_state, grant_disposition, evidence_reason, applied_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, outcome.outcomeId());
            statement.setString(2, outcome.coordinatorPolicyId());
            statement.setString(3, outcome.acknowledgmentJournalPolicyId());
            statement.setString(4, outcome.acknowledgmentPolicyId());
            statement.setString(5, outcome.acknowledgmentId());
            statement.setString(6, outcome.claimId());
            statement.setString(7, outcome.attemptId());
            statement.setString(8, outcome.grantId());
            statement.setString(9, outcome.handoffId());
            statement.setString(10, outcome.payloadSha256());
            statement.setString(11, outcome.destinationId());
            statement.setString(12, outcome.confirmation());
            statement.setString(13, outcome.acknowledgedAt().toString());
            statement.setString(14, outcome.terminalState().name());
            statement.setString(15, outcome.grantDisposition());
            statement.setString(16, outcome.evidenceReason());
            statement.setString(17, outcome.appliedAt().toString());
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
            WHERE attempt_id = ? AND grant_id = ? AND payload_sha256 = ? AND state = 'IN_FLIGHT'
            """)) {
            statement.setString(1, at.toString());
            statement.setString(2, detail);
            statement.setString(3, at.toString());
            statement.setString(4, outcome.attemptId());
            statement.setString(5, outcome.grantId());
            statement.setString(6, outcome.payloadSha256());
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
            SELECT * FROM sleeper_manual_message_terminal_outcomes WHERE claim_id = ?
            """)) {
            statement.setString(1, claimId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(readOutcome(rs)) : Optional.empty();
            }
        }
    }

    private static StoredOutcome readOutcome(ResultSet rs) throws SQLException {
        return new StoredOutcome(
            rs.getString("outcome_id"),
            rs.getString("coordinator_policy_id"),
            rs.getString("acknowledgment_journal_policy_id"),
            rs.getString("acknowledgment_policy_id"),
            rs.getString("acknowledgment_id"),
            rs.getString("claim_id"),
            rs.getString("attempt_id"),
            rs.getString("grant_id"),
            rs.getString("handoff_id"),
            rs.getString("payload_sha256"),
            rs.getString("destination_id"),
            rs.getString("confirmation"),
            Instant.parse(rs.getString("acknowledged_at")),
            TradeCounterExecutionAttemptRepository.State.valueOf(rs.getString("terminal_state")),
            rs.getString("grant_disposition"),
            rs.getString("evidence_reason"),
            Instant.parse(rs.getString("applied_at")));
    }

    public enum ApplyState {
        APPLIED,
        ALREADY_APPLIED,
        NOT_FOUND,
        MISMATCH,
        INVALID_STATE,
        GRANT_NOT_ACTIVE
    }

    public record ApplyResult(ApplyState state, StoredOutcome outcome, String reason) {
        public ApplyResult {
            Objects.requireNonNull(state, "state must not be null");
            reason = requireText(reason, "reason");
            if ((state == ApplyState.APPLIED || state == ApplyState.ALREADY_APPLIED)
                != (outcome != null)) {
                throw new IllegalArgumentException("only applied states may carry a stored outcome");
            }
        }
    }

    public record StoredOutcome(
        String outcomeId,
        String coordinatorPolicyId,
        String acknowledgmentJournalPolicyId,
        String acknowledgmentPolicyId,
        String acknowledgmentId,
        String claimId,
        String attemptId,
        String grantId,
        String handoffId,
        String payloadSha256,
        String destinationId,
        String confirmation,
        Instant acknowledgedAt,
        TradeCounterExecutionAttemptRepository.State terminalState,
        String grantDisposition,
        String evidenceReason,
        Instant appliedAt) {
        public StoredOutcome {
            outcomeId = requireText(outcomeId, "outcomeId");
            if (!COORDINATOR_POLICY_ID.equals(coordinatorPolicyId)) {
                throw new IllegalArgumentException("unexpected coordinatorPolicyId");
            }
            if (!SleeperManualMessageAcknowledgmentRepository.JOURNAL_POLICY_ID
                .equals(acknowledgmentJournalPolicyId)) {
                throw new IllegalArgumentException("unexpected acknowledgmentJournalPolicyId");
            }
            if (!SleeperManualMessageAcknowledgmentPolicy.POLICY_ID.equals(acknowledgmentPolicyId)) {
                throw new IllegalArgumentException("unexpected acknowledgmentPolicyId");
            }
            acknowledgmentId = requireText(acknowledgmentId, "acknowledgmentId");
            claimId = requireText(claimId, "claimId");
            attemptId = requireText(attemptId, "attemptId");
            grantId = requireText(grantId, "grantId");
            handoffId = requireText(handoffId, "handoffId");
            requireFingerprint(payloadSha256, "payloadSha256");
            destinationId = requireText(destinationId, "destinationId");
            if (!SleeperManualMessageAcknowledgmentPolicy.REQUIRED_CONFIRMATION.equals(confirmation)) {
                throw new IllegalArgumentException("manual-message outcome requires exact acknowledgment confirmation");
            }
            Objects.requireNonNull(acknowledgedAt, "acknowledgedAt must not be null");
            if (terminalState != TradeCounterExecutionAttemptRepository.State.SUCCEEDED) {
                throw new IllegalArgumentException("manual-message terminal outcome must be SUCCEEDED");
            }
            if (!"CONSUME".equals(grantDisposition)) {
                throw new IllegalArgumentException("manual-message success must consume authorization");
            }
            evidenceReason = requireText(evidenceReason, "evidenceReason");
            Objects.requireNonNull(appliedAt, "appliedAt must not be null");
        }
    }

    private record StoredAcknowledgment(
        String acknowledgmentId,
        String journalPolicyId,
        String acknowledgmentPolicyId,
        String claimId,
        String attemptId,
        String grantId,
        String handoffId,
        String payloadSha256,
        String destinationId,
        String confirmation,
        String completionEligibility,
        Instant acknowledgedAt,
        String evidenceReason) {
        StoredAcknowledgment {
            acknowledgmentId = requireText(acknowledgmentId, "acknowledgmentId");
            if (!SleeperManualMessageAcknowledgmentRepository.JOURNAL_POLICY_ID.equals(journalPolicyId)) {
                throw new IllegalArgumentException("unexpected acknowledgment journal provenance");
            }
            if (!SleeperManualMessageAcknowledgmentPolicy.POLICY_ID.equals(acknowledgmentPolicyId)) {
                throw new IllegalArgumentException("unexpected acknowledgment policy provenance");
            }
            claimId = requireText(claimId, "claimId");
            attemptId = requireText(attemptId, "attemptId");
            grantId = requireText(grantId, "grantId");
            handoffId = requireText(handoffId, "handoffId");
            requireFingerprint(payloadSha256, "payloadSha256");
            destinationId = requireText(destinationId, "destinationId");
            if (!SleeperManualMessageAcknowledgmentPolicy.REQUIRED_CONFIRMATION.equals(confirmation)) {
                throw new IllegalArgumentException("durable acknowledgment confirmation is not exact");
            }
            if (!SleeperManualMessageAcknowledgmentPolicy.LocalCompletionEligibility.MANUAL_MESSAGE_SUCCESS.name()
                .equals(completionEligibility)) {
                throw new IllegalArgumentException("durable acknowledgment is not success eligible");
            }
            Objects.requireNonNull(acknowledgedAt, "acknowledgedAt must not be null");
            evidenceReason = requireText(evidenceReason, "evidenceReason");
        }
    }

    private record TrustedState(
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
