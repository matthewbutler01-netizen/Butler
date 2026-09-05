package io.butler.bet.integration.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionOutcomeCoordinator;
import io.butler.bet.data.TradeCounterManualTerminalGuardInstaller;
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
 * Atomically finalizes one exact manual no-action acknowledgment as local FAILED + authorization
 * close. This coordinator mutates local Butler state only and never performs a Sleeper action.
 */
public final class SleeperManualCounterNoActionOutcomeCoordinator {
    public static final String COORDINATOR_POLICY_ID =
        "sleeper-manual-counter-no-action-outcome-coordinator-v1-durable-ack-atomic-failed-consume";

    private final Database database;

    public SleeperManualCounterNoActionOutcomeCoordinator(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void initialize() throws SQLException {
        new TradeCounterExecutionOutcomeCoordinator(database).initialize();
        new SleeperManualCounterNoActionAcknowledgmentRepository(database).initialize();
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sleeper_manual_counter_no_action_terminal_outcomes (
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
                    action TEXT NOT NULL,
                    destination_type TEXT NOT NULL,
                    destination_id TEXT NOT NULL,
                    confirmation TEXT NOT NULL,
                    acknowledged_at TEXT NOT NULL,
                    terminal_state TEXT NOT NULL,
                    grant_disposition TEXT NOT NULL,
                    evidence_reason TEXT NOT NULL,
                    applied_at TEXT NOT NULL,
                    FOREIGN KEY (acknowledgment_id)
                        REFERENCES sleeper_manual_counter_no_action_acknowledgments(acknowledgment_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (claim_id) REFERENCES trade_counter_execution_claims(claim_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (attempt_id) REFERENCES trade_counter_execution_attempts(attempt_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (grant_id) REFERENCES trade_counter_authorization_grants(grant_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (handoff_id) REFERENCES sleeper_manual_counter_handoffs(handoff_id)
                        ON DELETE RESTRICT,
                    CHECK (coordinator_policy_id = 'sleeper-manual-counter-no-action-outcome-coordinator-v1-durable-ack-atomic-failed-consume'),
                    CHECK (acknowledgment_journal_policy_id = 'sleeper-manual-counter-no-action-acknowledgment-journal-v1-exact-active-handoff-immutable'),
                    CHECK (acknowledgment_policy_id = 'sleeper-manual-counter-no-action-acknowledgment-v1-explicit-handoff-payload-confirmation'),
                    CHECK (length(payload_sha256) = 64),
                    CHECK (payload_sha256 NOT GLOB '*[^0-9a-f]*'),
                    CHECK (action IN ('SEND_NEGOTIATION_MESSAGE', 'SUBMIT_COUNTER_TRADE')),
                    CHECK (destination_type IN ('MANAGER', 'LEAGUE')),
                    CHECK (length(trim(destination_id)) > 0),
                    CHECK (confirmation = 'NO_EXTERNAL_ACTION_TAKEN'),
                    CHECK (terminal_state = 'FAILED'),
                    CHECK (grant_disposition = 'CONSUME'),
                    CHECK (length(trim(evidence_reason)) > 0),
                    CHECK ((action = 'SEND_NEGOTIATION_MESSAGE' AND destination_type = 'MANAGER')
                        OR (action = 'SUBMIT_COUNTER_TRADE' AND destination_type = 'LEAGUE'))
                )
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_sleeper_manual_no_action_terminal_outcome_trusted
                BEFORE INSERT ON sleeper_manual_counter_no_action_terminal_outcomes
                FOR EACH ROW
                WHEN NOT EXISTS (
                    SELECT 1
                    FROM sleeper_manual_counter_no_action_acknowledgments ack
                    JOIN sleeper_manual_counter_handoffs h ON h.handoff_id = ack.handoff_id
                    JOIN trade_counter_execution_attempts a ON a.attempt_id = ack.attempt_id
                    JOIN trade_counter_authorization_grants g ON g.grant_id = ack.grant_id
                    WHERE ack.acknowledgment_id = NEW.acknowledgment_id
                      AND ack.claim_id = NEW.claim_id
                      AND ack.attempt_id = NEW.attempt_id
                      AND ack.grant_id = NEW.grant_id
                      AND ack.handoff_id = NEW.handoff_id
                      AND ack.payload_sha256 = NEW.payload_sha256
                      AND ack.action = NEW.action
                      AND ack.destination_type = NEW.destination_type
                      AND ack.destination_id = NEW.destination_id
                      AND ack.confirmation = NEW.confirmation
                      AND ack.terminal_eligibility = 'CONFIRMED_NO_ACTION_FAILURE'
                      AND ack.attempt_terminal_state = 'FAILED'
                      AND ack.grant_disposition = 'CONSUME'
                      AND ack.acknowledged_at = NEW.acknowledged_at
                      AND h.claim_id = NEW.claim_id
                      AND h.attempt_id = NEW.attempt_id
                      AND h.grant_id = NEW.grant_id
                      AND h.handoff_id = NEW.handoff_id
                      AND h.payload_sha256 = NEW.payload_sha256
                      AND h.action = NEW.action
                      AND h.destination_type = NEW.destination_type
                      AND h.destination_id = NEW.destination_id
                      AND ((NEW.action = 'SEND_NEGOTIATION_MESSAGE'
                            AND h.payload_kind = 'NEGOTIATION_MESSAGE_TEXT'
                            AND h.reconciliation_mode = 'NO_OFFICIAL_READBACK')
                        OR (NEW.action = 'SUBMIT_COUNTER_TRADE'
                            AND h.payload_kind = 'COUNTER_TRADE_REQUEST_JSON'
                            AND h.reconciliation_mode = 'SLEEPER_TRANSACTION_READBACK'))
                      AND a.state = 'IN_FLIGHT'
                      AND a.grant_id = NEW.grant_id
                      AND a.payload_sha256 = NEW.payload_sha256
                      AND g.consumed_at IS NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM sleeper_manual_message_acknowledgments message_ack
                          WHERE message_ack.claim_id = NEW.claim_id
                             OR message_ack.attempt_id = NEW.attempt_id
                             OR message_ack.grant_id = NEW.grant_id
                             OR message_ack.handoff_id = NEW.handoff_id
                      )
                )
                BEGIN
                    SELECT RAISE(ABORT, 'manual no-action terminal outcome requires exact durable no-action evidence and active trusted handoff');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_sleeper_manual_no_action_terminal_outcome_immutable
                BEFORE UPDATE ON sleeper_manual_counter_no_action_terminal_outcomes
                FOR EACH ROW
                BEGIN
                    SELECT RAISE(ABORT, 'manual no-action terminal outcome is immutable');
                END
                """);
        }
        TradeCounterManualTerminalGuardInstaller.installSleeperNoActionSupport(database);
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
                        "The durable manual no-action acknowledgment was already finalized.");
                }

                StoredAcknowledgment acknowledgment = loadAcknowledgment(connection, claimId).orElse(null);
                if (acknowledgment == null) {
                    connection.rollback();
                    return new ApplyResult(
                        ApplyState.NOT_FOUND,
                        null,
                        "Durable BF-426 manual no-action acknowledgment was not found.");
                }
                TrustedState trusted = loadTrustedState(connection, acknowledgment).orElse(null);
                if (trusted == null) {
                    connection.rollback();
                    return new ApplyResult(
                        ApplyState.MISMATCH,
                        null,
                        "Durable no-action acknowledgment no longer matches the trusted manual handoff.");
                }
                if (trusted.attemptState() != TradeCounterExecutionAttemptRepository.State.IN_FLIGHT) {
                    connection.rollback();
                    return new ApplyResult(
                        ApplyState.INVALID_STATE,
                        null,
                        "Manual execution attempt is not IN_FLIGHT.");
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
                    acknowledgment.action(),
                    acknowledgment.destination(),
                    acknowledgment.confirmation(),
                    acknowledgment.acknowledgedAt(),
                    TradeCounterExecutionAttemptRepository.State.FAILED,
                    TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME,
                    acknowledgment.evidenceReason(),
                    appliedAt);
                insertOutcome(connection, outcome);

                String detail = "Explicit durable no-action acknowledgment confirmed no external action was taken for manual "
                    + outcome.action() + " handoff " + outcome.handoffId()
                    + "; retry requires fresh explicit authorization.";
                if (markFailed(connection, outcome, appliedAt, detail) != 1) {
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
                    "Durable manual no-action evidence was atomically finalized as FAILED and the one-shot authorization closed.");
            } catch (SQLException e) {
                connection.rollback();
                Optional<StoredOutcome> concurrent = findByClaimId(claimId);
                if (concurrent.isPresent()) {
                    return new ApplyResult(
                        ApplyState.ALREADY_APPLIED,
                        concurrent.get(),
                        "The durable manual no-action acknowledgment was concurrently finalized.");
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
                "The durable manual no-action acknowledgment was already finalized.");
        }
        try (var connection = database.openConnection()) {
            StoredAcknowledgment acknowledgment = loadAcknowledgment(connection, claimId).orElse(null);
            if (acknowledgment == null) {
                return new ApplyResult(ApplyState.NOT_FOUND, null,
                    "Durable BF-426 manual no-action acknowledgment was not found.");
            }
            TrustedState trusted = loadTrustedState(connection, acknowledgment).orElse(null);
            if (trusted == null) {
                return new ApplyResult(ApplyState.MISMATCH, null,
                    "Durable no-action acknowledgment no longer matches trusted manual state.");
            }
            if (trusted.consumedAt() != null) {
                return new ApplyResult(ApplyState.GRANT_NOT_ACTIVE, null,
                    "Trusted one-shot authorization grant is already consumed.");
            }
            return new ApplyResult(ApplyState.INVALID_STATE, null,
                "Manual execution attempt is no longer IN_FLIGHT.");
        }
    }

    private static Optional<StoredAcknowledgment> loadAcknowledgment(
        Connection connection,
        String claimId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT * FROM sleeper_manual_counter_no_action_acknowledgments WHERE claim_id = ?
            """)) {
            statement.setString(1, claimId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                var destination = new TradeCounterAuthorizationPolicy.Destination(
                    TradeCounterAuthorizationPolicy.DestinationType.valueOf(rs.getString("destination_type")),
                    rs.getString("destination_id"));
                return Optional.of(new StoredAcknowledgment(
                    rs.getString("acknowledgment_id"),
                    rs.getString("journal_policy_id"),
                    rs.getString("acknowledgment_policy_id"),
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
                    TradeCounterExecutionOutcomePolicy.GrantDisposition.valueOf(rs.getString("grant_disposition")),
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
              AND h.action = ?
              AND h.destination_type = ?
              AND h.destination_id = ?
              AND ((h.action = 'SEND_NEGOTIATION_MESSAGE'
                    AND h.payload_kind = 'NEGOTIATION_MESSAGE_TEXT'
                    AND h.reconciliation_mode = 'NO_OFFICIAL_READBACK')
                OR (h.action = 'SUBMIT_COUNTER_TRADE'
                    AND h.payload_kind = 'COUNTER_TRADE_REQUEST_JSON'
                    AND h.reconciliation_mode = 'SLEEPER_TRANSACTION_READBACK'))
              AND a.grant_id = h.grant_id
              AND a.payload_sha256 = h.payload_sha256
              AND NOT EXISTS (
                  SELECT 1 FROM sleeper_manual_message_acknowledgments message_ack
                  WHERE message_ack.claim_id = h.claim_id
                     OR message_ack.attempt_id = h.attempt_id
                     OR message_ack.grant_id = h.grant_id
                     OR message_ack.handoff_id = h.handoff_id
              )
            """)) {
            statement.setString(1, acknowledgment.claimId());
            statement.setString(2, acknowledgment.attemptId());
            statement.setString(3, acknowledgment.grantId());
            statement.setString(4, acknowledgment.handoffId());
            statement.setString(5, acknowledgment.payloadSha256());
            statement.setString(6, acknowledgment.action().name());
            statement.setString(7, acknowledgment.destination().type().name());
            statement.setString(8, acknowledgment.destination().id());
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
            INSERT INTO sleeper_manual_counter_no_action_terminal_outcomes(
                outcome_id, coordinator_policy_id, acknowledgment_journal_policy_id,
                acknowledgment_policy_id, acknowledgment_id, claim_id, attempt_id, grant_id,
                handoff_id, payload_sha256, action, destination_type, destination_id, confirmation,
                acknowledged_at, terminal_state, grant_disposition, evidence_reason, applied_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            statement.setString(11, outcome.action().name());
            statement.setString(12, outcome.destination().type().name());
            statement.setString(13, outcome.destination().id());
            statement.setString(14, outcome.confirmation());
            statement.setString(15, outcome.acknowledgedAt().toString());
            statement.setString(16, outcome.terminalState().name());
            statement.setString(17, outcome.grantDisposition().name());
            statement.setString(18, outcome.evidenceReason());
            statement.setString(19, outcome.appliedAt().toString());
            statement.executeUpdate();
        }
    }

    private static int markFailed(
        Connection connection,
        StoredOutcome outcome,
        Instant at,
        String detail) throws SQLException {
        try (var statement = connection.prepareStatement("""
            UPDATE trade_counter_execution_attempts
            SET state = 'FAILED', terminal_at = ?, outcome_detail = ?, updated_at = ?
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
            SELECT * FROM sleeper_manual_counter_no_action_terminal_outcomes WHERE claim_id = ?
            """)) {
            statement.setString(1, claimId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(readOutcome(rs)) : Optional.empty();
            }
        }
    }

    private static StoredOutcome readOutcome(ResultSet rs) throws SQLException {
        var destination = new TradeCounterAuthorizationPolicy.Destination(
            TradeCounterAuthorizationPolicy.DestinationType.valueOf(rs.getString("destination_type")),
            rs.getString("destination_id"));
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
            TradeCounterAuthorizationPolicy.Action.valueOf(rs.getString("action")),
            destination,
            rs.getString("confirmation"),
            Instant.parse(rs.getString("acknowledged_at")),
            TradeCounterExecutionAttemptRepository.State.valueOf(rs.getString("terminal_state")),
            TradeCounterExecutionOutcomePolicy.GrantDisposition.valueOf(rs.getString("grant_disposition")),
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
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        String confirmation,
        Instant acknowledgedAt,
        TradeCounterExecutionAttemptRepository.State terminalState,
        TradeCounterExecutionOutcomePolicy.GrantDisposition grantDisposition,
        String evidenceReason,
        Instant appliedAt) {
        public StoredOutcome {
            outcomeId = requireText(outcomeId, "outcomeId");
            if (!COORDINATOR_POLICY_ID.equals(coordinatorPolicyId)) {
                throw new IllegalArgumentException("unexpected coordinatorPolicyId");
            }
            if (!SleeperManualCounterNoActionAcknowledgmentRepository.JOURNAL_POLICY_ID
                .equals(acknowledgmentJournalPolicyId)) {
                throw new IllegalArgumentException("unexpected acknowledgmentJournalPolicyId");
            }
            if (!SleeperManualCounterNoActionAcknowledgmentPolicy.POLICY_ID.equals(acknowledgmentPolicyId)) {
                throw new IllegalArgumentException("unexpected acknowledgmentPolicyId");
            }
            acknowledgmentId = requireText(acknowledgmentId, "acknowledgmentId");
            claimId = requireText(claimId, "claimId");
            attemptId = requireText(attemptId, "attemptId");
            grantId = requireText(grantId, "grantId");
            handoffId = requireText(handoffId, "handoffId");
            requireFingerprint(payloadSha256, "payloadSha256");
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            if (!SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION.equals(confirmation)) {
                throw new IllegalArgumentException("no-action outcome requires exact acknowledgment confirmation");
            }
            Objects.requireNonNull(acknowledgedAt, "acknowledgedAt must not be null");
            if (terminalState != TradeCounterExecutionAttemptRepository.State.FAILED) {
                throw new IllegalArgumentException("manual no-action terminal outcome must be FAILED");
            }
            if (grantDisposition != TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME) {
                throw new IllegalArgumentException("manual no-action outcome must consume authorization");
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
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        String confirmation,
        String terminalEligibility,
        TradeCounterExecutionAttemptRepository.State attemptTerminalState,
        TradeCounterExecutionOutcomePolicy.GrantDisposition grantDisposition,
        Instant acknowledgedAt,
        String evidenceReason) {
        StoredAcknowledgment {
            acknowledgmentId = requireText(acknowledgmentId, "acknowledgmentId");
            if (!SleeperManualCounterNoActionAcknowledgmentRepository.JOURNAL_POLICY_ID.equals(journalPolicyId)) {
                throw new IllegalArgumentException("unexpected no-action acknowledgment journal provenance");
            }
            if (!SleeperManualCounterNoActionAcknowledgmentPolicy.POLICY_ID.equals(acknowledgmentPolicyId)) {
                throw new IllegalArgumentException("unexpected no-action acknowledgment policy provenance");
            }
            claimId = requireText(claimId, "claimId");
            attemptId = requireText(attemptId, "attemptId");
            grantId = requireText(grantId, "grantId");
            handoffId = requireText(handoffId, "handoffId");
            requireFingerprint(payloadSha256, "payloadSha256");
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            if (!SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION.equals(confirmation)) {
                throw new IllegalArgumentException("durable no-action acknowledgment confirmation is not exact");
            }
            if (!SleeperManualCounterNoActionAcknowledgmentPolicy.LocalTerminalEligibility
                .CONFIRMED_NO_ACTION_FAILURE.name().equals(terminalEligibility)) {
                throw new IllegalArgumentException("durable no-action acknowledgment is not failure eligible");
            }
            if (attemptTerminalState != TradeCounterExecutionAttemptRepository.State.FAILED) {
                throw new IllegalArgumentException("durable no-action acknowledgment does not require FAILED");
            }
            if (grantDisposition != TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME) {
                throw new IllegalArgumentException("durable no-action acknowledgment does not close authorization");
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
