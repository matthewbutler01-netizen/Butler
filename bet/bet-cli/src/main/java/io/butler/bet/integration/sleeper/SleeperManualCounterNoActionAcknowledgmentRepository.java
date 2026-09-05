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
 * Durable immutable audit store for exact BF-425 manual no-action acknowledgment evidence.
 * This repository never terminalizes an attempt or consumes authorization.
 */
public final class SleeperManualCounterNoActionAcknowledgmentRepository {
    public static final String JOURNAL_POLICY_ID =
        "sleeper-manual-counter-no-action-acknowledgment-journal-v1-exact-active-handoff-immutable";

    private final Database database;

    public SleeperManualCounterNoActionAcknowledgmentRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void initialize() throws SQLException {
        new SleeperManualMessageAcknowledgmentRepository(database).initialize();
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sleeper_manual_counter_no_action_acknowledgments (
                    acknowledgment_id TEXT PRIMARY KEY,
                    journal_policy_id TEXT NOT NULL,
                    acknowledgment_policy_id TEXT NOT NULL,
                    handoff_journal_policy_id TEXT NOT NULL,
                    handoff_service_id TEXT NOT NULL,
                    claim_id TEXT NOT NULL UNIQUE,
                    attempt_id TEXT NOT NULL UNIQUE,
                    grant_id TEXT NOT NULL UNIQUE,
                    handoff_id TEXT NOT NULL UNIQUE,
                    payload_sha256 TEXT NOT NULL,
                    action TEXT NOT NULL,
                    destination_type TEXT NOT NULL,
                    destination_id TEXT NOT NULL,
                    confirmation TEXT NOT NULL,
                    terminal_eligibility TEXT NOT NULL,
                    attempt_terminal_state TEXT NOT NULL,
                    grant_disposition TEXT NOT NULL,
                    presented_at TEXT NOT NULL,
                    acknowledged_at TEXT NOT NULL,
                    evidence_reason TEXT NOT NULL,
                    recorded_at TEXT NOT NULL,
                    FOREIGN KEY (claim_id) REFERENCES trade_counter_execution_claims(claim_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (attempt_id) REFERENCES trade_counter_execution_attempts(attempt_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (grant_id) REFERENCES trade_counter_authorization_grants(grant_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (handoff_id) REFERENCES sleeper_manual_counter_handoffs(handoff_id)
                        ON DELETE RESTRICT,
                    CHECK (journal_policy_id = 'sleeper-manual-counter-no-action-acknowledgment-journal-v1-exact-active-handoff-immutable'),
                    CHECK (acknowledgment_policy_id = 'sleeper-manual-counter-no-action-acknowledgment-v1-explicit-handoff-payload-confirmation'),
                    CHECK (handoff_journal_policy_id = 'sleeper-manual-counter-handoff-journal-v1-first-presentation-immutable'),
                    CHECK (handoff_service_id = 'sleeper-manual-counter-handoff-v1-trusted-claim-present-only'),
                    CHECK (length(payload_sha256) = 64),
                    CHECK (payload_sha256 NOT GLOB '*[^0-9a-f]*'),
                    CHECK (action IN ('SEND_NEGOTIATION_MESSAGE', 'SUBMIT_COUNTER_TRADE')),
                    CHECK (destination_type IN ('MANAGER', 'LEAGUE')),
                    CHECK (confirmation = 'NO_EXTERNAL_ACTION_TAKEN'),
                    CHECK (terminal_eligibility = 'CONFIRMED_NO_ACTION_FAILURE'),
                    CHECK (attempt_terminal_state = 'FAILED'),
                    CHECK (grant_disposition = 'CONSUME'),
                    CHECK (length(trim(destination_id)) > 0),
                    CHECK (length(trim(evidence_reason)) > 0),
                    CHECK ((action = 'SEND_NEGOTIATION_MESSAGE' AND destination_type = 'MANAGER')
                        OR (action = 'SUBMIT_COUNTER_TRADE' AND destination_type = 'LEAGUE'))
                )
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_sleeper_manual_no_action_trusted_active_handoff
                BEFORE INSERT ON sleeper_manual_counter_no_action_acknowledgments
                FOR EACH ROW
                WHEN NOT EXISTS (
                    SELECT 1
                    FROM sleeper_manual_counter_handoffs h
                    JOIN trade_counter_execution_attempts a ON a.attempt_id = h.attempt_id
                    JOIN trade_counter_authorization_grants g ON g.grant_id = h.grant_id
                    WHERE h.claim_id = NEW.claim_id
                      AND h.attempt_id = NEW.attempt_id
                      AND h.grant_id = NEW.grant_id
                      AND h.handoff_id = NEW.handoff_id
                      AND h.payload_sha256 = NEW.payload_sha256
                      AND h.action = NEW.action
                      AND h.destination_type = NEW.destination_type
                      AND h.destination_id = NEW.destination_id
                      AND h.presented_at = NEW.presented_at
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
                )
                BEGIN
                    SELECT RAISE(ABORT, 'manual no-action acknowledgment requires matching active trusted handoff');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_sleeper_manual_no_action_conflicts_message_ack
                BEFORE INSERT ON sleeper_manual_counter_no_action_acknowledgments
                FOR EACH ROW
                WHEN EXISTS (
                    SELECT 1 FROM sleeper_manual_message_acknowledgments ack
                    WHERE ack.claim_id = NEW.claim_id
                       OR ack.attempt_id = NEW.attempt_id
                       OR ack.grant_id = NEW.grant_id
                       OR ack.handoff_id = NEW.handoff_id
                )
                BEGIN
                    SELECT RAISE(ABORT, 'manual no-action evidence conflicts with durable sent-message acknowledgment');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_sleeper_manual_no_action_immutable
                BEFORE UPDATE ON sleeper_manual_counter_no_action_acknowledgments
                FOR EACH ROW
                BEGIN
                    SELECT RAISE(ABORT, 'manual no-action acknowledgment is immutable');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_sleeper_manual_no_action_delete_immutable
                BEFORE DELETE ON sleeper_manual_counter_no_action_acknowledgments
                FOR EACH ROW
                BEGIN
                    SELECT RAISE(ABORT, 'manual no-action acknowledgment is immutable');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_sleeper_manual_message_ack_conflicts_no_action
                BEFORE INSERT ON sleeper_manual_message_acknowledgments
                FOR EACH ROW
                WHEN EXISTS (
                    SELECT 1 FROM sleeper_manual_counter_no_action_acknowledgments no_action
                    WHERE no_action.claim_id = NEW.claim_id
                       OR no_action.attempt_id = NEW.attempt_id
                       OR no_action.grant_id = NEW.grant_id
                       OR no_action.handoff_id = NEW.handoff_id
                )
                BEGIN
                    SELECT RAISE(ABORT, 'manual sent-message acknowledgment conflicts with durable no-action evidence');
                END
                """);
        }
    }

    public RecordResult record(
        SleeperManualCounterNoActionAcknowledgmentPolicy.Decision decision,
        Instant recordedAt) throws SQLException {
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");

        if (decision.state() != SleeperManualCounterNoActionAcknowledgmentPolicy.State.ACKNOWLEDGED
            || decision.localTerminalEligibility()
                != SleeperManualCounterNoActionAcknowledgmentPolicy.LocalTerminalEligibility.CONFIRMED_NO_ACTION_FAILURE
            || decision.attemptTerminalState() != TradeCounterExecutionAttemptRepository.State.FAILED
            || decision.grantDisposition() != TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME) {
            return new RecordResult(
                RecordState.NOT_ELIGIBLE,
                null,
                "Only exact BF-425 acknowledged no-action evidence may be durably recorded.");
        }

        initialize();
        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<StoredAcknowledgment> existing = findByClaimId(connection, decision.claimId());
                if (existing.isPresent()) {
                    if (matches(existing.get(), decision)) {
                        connection.commit();
                        return new RecordResult(
                            RecordState.ALREADY_RECORDED,
                            existing.get(),
                            "The exact same manual no-action acknowledgment was already recorded.");
                    }
                    connection.rollback();
                    return new RecordResult(
                        RecordState.MISMATCH,
                        null,
                        "A different durable no-action acknowledgment already exists for this claim.");
                }

                if (hasConflictingMessageAcknowledgment(connection, decision)) {
                    connection.rollback();
                    return new RecordResult(
                        RecordState.CONFLICTING_SUCCESS_EVIDENCE,
                        null,
                        "Durable SENT_EXACT_MESSAGE evidence already exists for this manual message handoff.");
                }

                TrustedSnapshot trusted = loadTrustedSnapshot(connection, decision.claimId()).orElse(null);
                if (trusted == null) {
                    connection.rollback();
                    return new RecordResult(
                        RecordState.NOT_FOUND,
                        null,
                        "Trusted manual handoff was not found.");
                }
                if (!matches(trusted, decision)) {
                    connection.rollback();
                    return new RecordResult(
                        RecordState.MISMATCH,
                        null,
                        "BF-425 no-action acknowledgment does not match trusted persisted handoff coordinates.");
                }
                if (trusted.attemptState() != TradeCounterExecutionAttemptRepository.State.IN_FLIGHT) {
                    connection.rollback();
                    return new RecordResult(
                        RecordState.INVALID_STATE,
                        null,
                        "Manual execution attempt is not IN_FLIGHT.");
                }
                if (trusted.consumedAt() != null) {
                    connection.rollback();
                    return new RecordResult(
                        RecordState.GRANT_NOT_ACTIVE,
                        null,
                        "Trusted one-shot authorization grant is already consumed.");
                }

                StoredAcknowledgment stored = new StoredAcknowledgment(
                    UUID.randomUUID().toString(),
                    JOURNAL_POLICY_ID,
                    decision.policyId(),
                    decision.handoffJournalPolicyId(),
                    decision.handoffServiceId(),
                    decision.claimId(),
                    decision.attemptId(),
                    decision.grantId(),
                    decision.handoffId(),
                    decision.payloadSha256(),
                    decision.action(),
                    decision.destination(),
                    decision.suppliedConfirmation(),
                    decision.localTerminalEligibility(),
                    Objects.requireNonNull(decision.attemptTerminalState(),
                        "acknowledged decision requires attemptTerminalState"),
                    decision.grantDisposition(),
                    decision.presentedAt(),
                    Objects.requireNonNull(decision.acknowledgedAt(),
                        "acknowledged decision requires acknowledgedAt"),
                    decision.reason(),
                    recordedAt);
                insert(connection, stored);
                connection.commit();
                return new RecordResult(
                    RecordState.RECORDED,
                    stored,
                    "Exact manual no-action acknowledgment was durably recorded without changing execution or authorization state.");
            } catch (SQLException e) {
                connection.rollback();
                Optional<StoredAcknowledgment> concurrent = findByClaimId(decision.claimId());
                if (concurrent.isPresent() && matches(concurrent.get(), decision)) {
                    return new RecordResult(
                        RecordState.ALREADY_RECORDED,
                        concurrent.get(),
                        "The exact same manual no-action acknowledgment was concurrently recorded.");
                }
                if (hasConflictingMessageAcknowledgment(decision)) {
                    return new RecordResult(
                        RecordState.CONFLICTING_SUCCESS_EVIDENCE,
                        null,
                        "Durable SENT_EXACT_MESSAGE evidence concurrently won the conflicting evidence race.");
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

    public Optional<StoredAcknowledgment> findByClaimId(String claimId) throws SQLException {
        claimId = requireText(claimId, "claimId");
        initialize();
        try (var connection = database.openConnection()) {
            return findByClaimId(connection, claimId);
        }
    }

    private boolean hasConflictingMessageAcknowledgment(
        SleeperManualCounterNoActionAcknowledgmentPolicy.Decision decision) throws SQLException {
        try (var connection = database.openConnection()) {
            return hasConflictingMessageAcknowledgment(connection, decision);
        }
    }

    private static boolean hasConflictingMessageAcknowledgment(
        Connection connection,
        SleeperManualCounterNoActionAcknowledgmentPolicy.Decision decision) throws SQLException {
        if (decision.action() != TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE) return false;
        try (var statement = connection.prepareStatement("""
            SELECT 1 FROM sleeper_manual_message_acknowledgments
            WHERE claim_id = ? OR attempt_id = ? OR grant_id = ? OR handoff_id = ?
            LIMIT 1
            """)) {
            statement.setString(1, decision.claimId());
            statement.setString(2, decision.attemptId());
            statement.setString(3, decision.grantId());
            statement.setString(4, decision.handoffId());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Optional<TrustedSnapshot> loadTrustedSnapshot(
        Connection connection,
        String claimId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT h.claim_id, h.attempt_id, h.grant_id, h.handoff_id, h.payload_sha256,
                   h.action, h.destination_type, h.destination_id, h.presented_at,
                   a.state, g.consumed_at
            FROM sleeper_manual_counter_handoffs h
            JOIN trade_counter_execution_attempts a ON a.attempt_id = h.attempt_id
            JOIN trade_counter_authorization_grants g ON g.grant_id = h.grant_id
            WHERE h.claim_id = ?
            """)) {
            statement.setString(1, claimId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String consumed = rs.getString("consumed_at");
                var destination = new TradeCounterAuthorizationPolicy.Destination(
                    TradeCounterAuthorizationPolicy.DestinationType.valueOf(rs.getString("destination_type")),
                    rs.getString("destination_id"));
                return Optional.of(new TrustedSnapshot(
                    rs.getString("claim_id"),
                    rs.getString("attempt_id"),
                    rs.getString("grant_id"),
                    rs.getString("handoff_id"),
                    rs.getString("payload_sha256"),
                    TradeCounterAuthorizationPolicy.Action.valueOf(rs.getString("action")),
                    destination,
                    Instant.parse(rs.getString("presented_at")),
                    TradeCounterExecutionAttemptRepository.State.valueOf(rs.getString("state")),
                    consumed == null ? null : Instant.parse(consumed)));
            }
        }
    }

    private static void insert(Connection connection, StoredAcknowledgment stored) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO sleeper_manual_counter_no_action_acknowledgments(
                acknowledgment_id, journal_policy_id, acknowledgment_policy_id,
                handoff_journal_policy_id, handoff_service_id, claim_id, attempt_id,
                grant_id, handoff_id, payload_sha256, action, destination_type,
                destination_id, confirmation, terminal_eligibility, attempt_terminal_state,
                grant_disposition, presented_at, acknowledged_at, evidence_reason, recorded_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, stored.acknowledgmentId());
            statement.setString(2, stored.journalPolicyId());
            statement.setString(3, stored.acknowledgmentPolicyId());
            statement.setString(4, stored.handoffJournalPolicyId());
            statement.setString(5, stored.handoffServiceId());
            statement.setString(6, stored.claimId());
            statement.setString(7, stored.attemptId());
            statement.setString(8, stored.grantId());
            statement.setString(9, stored.handoffId());
            statement.setString(10, stored.payloadSha256());
            statement.setString(11, stored.action().name());
            statement.setString(12, stored.destination().type().name());
            statement.setString(13, stored.destination().id());
            statement.setString(14, stored.confirmation());
            statement.setString(15, stored.localTerminalEligibility().name());
            statement.setString(16, stored.attemptTerminalState().name());
            statement.setString(17, stored.grantDisposition().name());
            statement.setString(18, stored.presentedAt().toString());
            statement.setString(19, stored.acknowledgedAt().toString());
            statement.setString(20, stored.evidenceReason());
            statement.setString(21, stored.recordedAt().toString());
            statement.executeUpdate();
        }
    }

    private static Optional<StoredAcknowledgment> findByClaimId(
        Connection connection,
        String claimId) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT * FROM sleeper_manual_counter_no_action_acknowledgments WHERE claim_id = ?
            """)) {
            statement.setString(1, claimId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    private static StoredAcknowledgment read(ResultSet rs) throws SQLException {
        var destination = new TradeCounterAuthorizationPolicy.Destination(
            TradeCounterAuthorizationPolicy.DestinationType.valueOf(rs.getString("destination_type")),
            rs.getString("destination_id"));
        return new StoredAcknowledgment(
            rs.getString("acknowledgment_id"),
            rs.getString("journal_policy_id"),
            rs.getString("acknowledgment_policy_id"),
            rs.getString("handoff_journal_policy_id"),
            rs.getString("handoff_service_id"),
            rs.getString("claim_id"),
            rs.getString("attempt_id"),
            rs.getString("grant_id"),
            rs.getString("handoff_id"),
            rs.getString("payload_sha256"),
            TradeCounterAuthorizationPolicy.Action.valueOf(rs.getString("action")),
            destination,
            rs.getString("confirmation"),
            SleeperManualCounterNoActionAcknowledgmentPolicy.LocalTerminalEligibility.valueOf(
                rs.getString("terminal_eligibility")),
            TradeCounterExecutionAttemptRepository.State.valueOf(rs.getString("attempt_terminal_state")),
            TradeCounterExecutionOutcomePolicy.GrantDisposition.valueOf(rs.getString("grant_disposition")),
            Instant.parse(rs.getString("presented_at")),
            Instant.parse(rs.getString("acknowledged_at")),
            rs.getString("evidence_reason"),
            Instant.parse(rs.getString("recorded_at")));
    }

    private static boolean matches(
        StoredAcknowledgment stored,
        SleeperManualCounterNoActionAcknowledgmentPolicy.Decision decision) {
        return stored.acknowledgmentPolicyId().equals(decision.policyId())
            && stored.handoffJournalPolicyId().equals(decision.handoffJournalPolicyId())
            && stored.handoffServiceId().equals(decision.handoffServiceId())
            && stored.claimId().equals(decision.claimId())
            && stored.attemptId().equals(decision.attemptId())
            && stored.grantId().equals(decision.grantId())
            && stored.handoffId().equals(decision.handoffId())
            && stored.payloadSha256().equals(decision.payloadSha256())
            && stored.action() == decision.action()
            && stored.destination().equals(decision.destination())
            && stored.confirmation().equals(decision.suppliedConfirmation())
            && stored.localTerminalEligibility() == decision.localTerminalEligibility()
            && stored.attemptTerminalState() == decision.attemptTerminalState()
            && stored.grantDisposition() == decision.grantDisposition()
            && stored.presentedAt().equals(decision.presentedAt())
            && stored.acknowledgedAt().equals(decision.acknowledgedAt());
    }

    private static boolean matches(
        TrustedSnapshot trusted,
        SleeperManualCounterNoActionAcknowledgmentPolicy.Decision decision) {
        return trusted.claimId().equals(decision.claimId())
            && trusted.attemptId().equals(decision.attemptId())
            && trusted.grantId().equals(decision.grantId())
            && trusted.handoffId().equals(decision.handoffId())
            && trusted.payloadSha256().equals(decision.payloadSha256())
            && trusted.action() == decision.action()
            && trusted.destination().equals(decision.destination())
            && trusted.presentedAt().equals(decision.presentedAt());
    }

    public enum RecordState {
        RECORDED,
        ALREADY_RECORDED,
        NOT_ELIGIBLE,
        NOT_FOUND,
        MISMATCH,
        CONFLICTING_SUCCESS_EVIDENCE,
        INVALID_STATE,
        GRANT_NOT_ACTIVE
    }

    public record RecordResult(RecordState state, StoredAcknowledgment acknowledgment, String reason) {
        public RecordResult {
            Objects.requireNonNull(state, "state must not be null");
            reason = requireText(reason, "reason");
            if ((state == RecordState.RECORDED || state == RecordState.ALREADY_RECORDED)
                != (acknowledgment != null)) {
                throw new IllegalArgumentException("only recorded states may carry an acknowledgment");
            }
        }
    }

    public record StoredAcknowledgment(
        String acknowledgmentId,
        String journalPolicyId,
        String acknowledgmentPolicyId,
        String handoffJournalPolicyId,
        String handoffServiceId,
        String claimId,
        String attemptId,
        String grantId,
        String handoffId,
        String payloadSha256,
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        String confirmation,
        SleeperManualCounterNoActionAcknowledgmentPolicy.LocalTerminalEligibility localTerminalEligibility,
        TradeCounterExecutionAttemptRepository.State attemptTerminalState,
        TradeCounterExecutionOutcomePolicy.GrantDisposition grantDisposition,
        Instant presentedAt,
        Instant acknowledgedAt,
        String evidenceReason,
        Instant recordedAt) {
        public StoredAcknowledgment {
            acknowledgmentId = requireText(acknowledgmentId, "acknowledgmentId");
            if (!JOURNAL_POLICY_ID.equals(journalPolicyId)) throw new IllegalArgumentException("unexpected journalPolicyId");
            if (!SleeperManualCounterNoActionAcknowledgmentPolicy.POLICY_ID.equals(acknowledgmentPolicyId)) {
                throw new IllegalArgumentException("unexpected acknowledgmentPolicyId");
            }
            if (!SleeperManualCounterHandoffRepository.JOURNAL_POLICY_ID.equals(handoffJournalPolicyId)) {
                throw new IllegalArgumentException("unexpected handoffJournalPolicyId");
            }
            if (!SleeperManualCounterHandoffService.SERVICE_ID.equals(handoffServiceId)) {
                throw new IllegalArgumentException("unexpected handoffServiceId");
            }
            claimId = requireText(claimId, "claimId");
            attemptId = requireText(attemptId, "attemptId");
            grantId = requireText(grantId, "grantId");
            handoffId = requireText(handoffId, "handoffId");
            requireFingerprint(payloadSha256, "payloadSha256");
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            confirmation = requireText(confirmation, "confirmation");
            if (!SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION.equals(confirmation)) {
                throw new IllegalArgumentException("unexpected confirmation");
            }
            if (localTerminalEligibility
                != SleeperManualCounterNoActionAcknowledgmentPolicy.LocalTerminalEligibility.CONFIRMED_NO_ACTION_FAILURE) {
                throw new IllegalArgumentException("unexpected localTerminalEligibility");
            }
            if (attemptTerminalState != TradeCounterExecutionAttemptRepository.State.FAILED) {
                throw new IllegalArgumentException("unexpected attemptTerminalState");
            }
            if (grantDisposition != TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME) {
                throw new IllegalArgumentException("unexpected grantDisposition");
            }
            Objects.requireNonNull(presentedAt, "presentedAt must not be null");
            Objects.requireNonNull(acknowledgedAt, "acknowledgedAt must not be null");
            if (acknowledgedAt.isBefore(presentedAt)) {
                throw new IllegalArgumentException("acknowledgedAt cannot predate presentedAt");
            }
            evidenceReason = requireText(evidenceReason, "evidenceReason");
            Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        }
    }

    private record TrustedSnapshot(
        String claimId,
        String attemptId,
        String grantId,
        String handoffId,
        String payloadSha256,
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        Instant presentedAt,
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
