package io.butler.bet.integration.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterExecutionRequestRepository;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable audit record that Butler presented one governed manual Sleeper handoff. */
public final class SleeperManualCounterHandoffRepository {
    public static final String JOURNAL_POLICY_ID =
        "sleeper-manual-counter-handoff-journal-v1-first-presentation-immutable";

    private final Database database;
    private final SleeperManualCounterHandoffService handoffService;

    public SleeperManualCounterHandoffRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.handoffService = new SleeperManualCounterHandoffService(database);
    }

    public void initialize() throws SQLException {
        new TradeCounterExecutionRequestRepository(database).initialize();
        try (var connection = database.openConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sleeper_manual_counter_handoffs (
                    handoff_id TEXT PRIMARY KEY,
                    journal_policy_id TEXT NOT NULL,
                    handoff_service_id TEXT NOT NULL,
                    capability_policy_id TEXT NOT NULL,
                    execution_request_policy_id TEXT NOT NULL,
                    claim_id TEXT NOT NULL UNIQUE,
                    attempt_id TEXT NOT NULL UNIQUE,
                    grant_id TEXT NOT NULL UNIQUE,
                    proposal_fingerprint TEXT NOT NULL,
                    action TEXT NOT NULL,
                    destination_type TEXT NOT NULL,
                    destination_id TEXT NOT NULL,
                    payload_kind TEXT NOT NULL,
                    payload_sha256 TEXT NOT NULL,
                    reconciliation_mode TEXT NOT NULL,
                    presented_at TEXT NOT NULL,
                    FOREIGN KEY (claim_id) REFERENCES trade_counter_execution_claims(claim_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (attempt_id) REFERENCES trade_counter_execution_attempts(attempt_id)
                        ON DELETE RESTRICT,
                    FOREIGN KEY (grant_id) REFERENCES trade_counter_authorization_grants(grant_id)
                        ON DELETE RESTRICT,
                    CHECK (journal_policy_id = 'sleeper-manual-counter-handoff-journal-v1-first-presentation-immutable'),
                    CHECK (handoff_service_id = 'sleeper-manual-counter-handoff-v1-trusted-claim-present-only'),
                    CHECK (capability_policy_id = 'sleeper-platform-capability-v1-official-read-only-manual-write-handoff'),
                    CHECK (execution_request_policy_id = 'trade-counter-execution-request-v1-persisted-claim-attempt-only'),
                    CHECK (length(proposal_fingerprint) = 64),
                    CHECK (proposal_fingerprint NOT GLOB '*[^0-9a-f]*'),
                    CHECK (length(payload_sha256) = 64),
                    CHECK (payload_sha256 NOT GLOB '*[^0-9a-f]*'),
                    CHECK (action IN ('SEND_NEGOTIATION_MESSAGE', 'SUBMIT_COUNTER_TRADE')),
                    CHECK (destination_type IN ('MANAGER', 'LEAGUE')),
                    CHECK (payload_kind IN ('NEGOTIATION_MESSAGE_TEXT', 'COUNTER_TRADE_REQUEST_JSON')),
                    CHECK (reconciliation_mode IN ('NO_OFFICIAL_READBACK', 'SLEEPER_TRANSACTION_READBACK')),
                    CHECK ((action = 'SEND_NEGOTIATION_MESSAGE'
                            AND destination_type = 'MANAGER'
                            AND payload_kind = 'NEGOTIATION_MESSAGE_TEXT'
                            AND reconciliation_mode = 'NO_OFFICIAL_READBACK')
                        OR (action = 'SUBMIT_COUNTER_TRADE'
                            AND destination_type = 'LEAGUE'
                            AND payload_kind = 'COUNTER_TRADE_REQUEST_JSON'
                            AND reconciliation_mode = 'SLEEPER_TRANSACTION_READBACK'))
                )
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_sleeper_manual_counter_handoff_trusted_request
                BEFORE INSERT ON sleeper_manual_counter_handoffs
                FOR EACH ROW
                WHEN NOT EXISTS (
                    SELECT 1
                    FROM trade_counter_execution_claims c
                    JOIN trade_counter_execution_attempts a ON a.attempt_id = c.attempt_id
                    JOIN trade_counter_authorization_grants g ON g.grant_id = c.grant_id
                    WHERE c.claim_id = NEW.claim_id
                      AND c.attempt_id = NEW.attempt_id
                      AND c.grant_id = NEW.grant_id
                      AND c.proposal_fingerprint = NEW.proposal_fingerprint
                      AND c.action = NEW.action
                      AND c.destination_type = NEW.destination_type
                      AND c.destination_id = NEW.destination_id
                      AND a.state = 'IN_FLIGHT'
                      AND a.payload_kind = NEW.payload_kind
                      AND a.payload_sha256 = NEW.payload_sha256
                      AND g.consumed_at IS NULL
                )
                BEGIN
                    SELECT RAISE(ABORT, 'manual handoff requires matching active IN_FLIGHT trusted execution request');
                END
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS trg_sleeper_manual_counter_handoff_immutable
                BEFORE UPDATE ON sleeper_manual_counter_handoffs
                FOR EACH ROW
                BEGIN
                    SELECT RAISE(ABORT, 'manual handoff presentation record is immutable');
                END
                """);
        }
    }

    public RecordResult recordPresented(String claimId, Instant presentedAt) throws SQLException {
        claimId = requireText(claimId, "claimId");
        Objects.requireNonNull(presentedAt, "presentedAt must not be null");

        var handoffResult = handoffService.prepare(claimId);
        if (handoffResult.state() != SleeperManualCounterHandoffService.State.HANDOFF_READY) {
            return new RecordResult(
                RecordState.NOT_AVAILABLE,
                null,
                handoffResult.reason());
        }
        var handoff = handoffResult.handoff();
        initialize();

        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<PresentedHandoff> existing = findByClaimId(connection, claimId);
                if (existing.isPresent()) {
                    requireSameHandoff(existing.get(), handoff);
                    connection.commit();
                    return new RecordResult(
                        RecordState.ALREADY_PRESENTED,
                        existing.get(),
                        "The exact same handoff was already presented; the original presented_at boundary is preserved.");
                }

                var stored = new PresentedHandoff(
                    UUID.randomUUID().toString(),
                    JOURNAL_POLICY_ID,
                    handoff.serviceId(),
                    handoff.capabilityPolicyId(),
                    handoff.executionRequestPolicyId(),
                    handoff.claimId(),
                    handoff.attemptId(),
                    handoff.grantId(),
                    handoff.proposalFingerprint(),
                    handoff.action(),
                    handoff.destination(),
                    handoff.payloadKind().name(),
                    handoff.payloadSha256(),
                    handoff.reconciliationMode(),
                    presentedAt);
                insert(connection, stored);
                connection.commit();
                return new RecordResult(
                    RecordState.PRESENTED,
                    stored,
                    "The governed manual Sleeper handoff presentation was durably recorded.");
            } catch (SQLException e) {
                connection.rollback();
                Optional<PresentedHandoff> concurrent = findByClaimId(claimId);
                if (concurrent.isPresent()) {
                    requireSameHandoff(concurrent.get(), handoff);
                    return new RecordResult(
                        RecordState.ALREADY_PRESENTED,
                        concurrent.get(),
                        "The exact handoff was concurrently recorded; the first presented_at boundary is authoritative.");
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

    public Optional<PresentedHandoff> findByClaimId(String claimId) throws SQLException {
        claimId = requireText(claimId, "claimId");
        initialize();
        try (var connection = database.openConnection()) {
            return findByClaimId(connection, claimId);
        }
    }

    public Optional<PresentedHandoff> findByGrantId(String grantId) throws SQLException {
        grantId = requireText(grantId, "grantId");
        initialize();
        try (var connection = database.openConnection()) {
            return findByGrantId(connection, grantId);
        }
    }

    private static Optional<PresentedHandoff> findByClaimId(Connection connection, String claimId)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT * FROM sleeper_manual_counter_handoffs WHERE claim_id = ?
            """)) {
            statement.setString(1, claimId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    private static Optional<PresentedHandoff> findByGrantId(Connection connection, String grantId)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT * FROM sleeper_manual_counter_handoffs WHERE grant_id = ?
            """)) {
            statement.setString(1, grantId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    private static void insert(Connection connection, PresentedHandoff handoff) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO sleeper_manual_counter_handoffs(
                handoff_id, journal_policy_id, handoff_service_id, capability_policy_id,
                execution_request_policy_id, claim_id, attempt_id, grant_id,
                proposal_fingerprint, action, destination_type, destination_id,
                payload_kind, payload_sha256, reconciliation_mode, presented_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, handoff.handoffId());
            statement.setString(2, handoff.journalPolicyId());
            statement.setString(3, handoff.handoffServiceId());
            statement.setString(4, handoff.capabilityPolicyId());
            statement.setString(5, handoff.executionRequestPolicyId());
            statement.setString(6, handoff.claimId());
            statement.setString(7, handoff.attemptId());
            statement.setString(8, handoff.grantId());
            statement.setString(9, handoff.proposalFingerprint());
            statement.setString(10, handoff.action().name());
            statement.setString(11, handoff.destination().type().name());
            statement.setString(12, handoff.destination().id());
            statement.setString(13, handoff.payloadKind());
            statement.setString(14, handoff.payloadSha256());
            statement.setString(15, handoff.reconciliationMode().name());
            statement.setString(16, handoff.presentedAt().toString());
            statement.executeUpdate();
        }
    }

    private static PresentedHandoff read(ResultSet rs) throws SQLException {
        return new PresentedHandoff(
            rs.getString("handoff_id"),
            rs.getString("journal_policy_id"),
            rs.getString("handoff_service_id"),
            rs.getString("capability_policy_id"),
            rs.getString("execution_request_policy_id"),
            rs.getString("claim_id"),
            rs.getString("attempt_id"),
            rs.getString("grant_id"),
            rs.getString("proposal_fingerprint"),
            TradeCounterAuthorizationPolicy.Action.valueOf(rs.getString("action")),
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.valueOf(rs.getString("destination_type")),
                rs.getString("destination_id")),
            rs.getString("payload_kind"),
            rs.getString("payload_sha256"),
            SleeperManualCounterHandoffService.ReconciliationMode.valueOf(
                rs.getString("reconciliation_mode")),
            Instant.parse(rs.getString("presented_at")));
    }

    private static void requireSameHandoff(
        PresentedHandoff stored,
        SleeperManualCounterHandoffService.Handoff handoff) {
        boolean same = stored.handoffServiceId().equals(handoff.serviceId())
            && stored.capabilityPolicyId().equals(handoff.capabilityPolicyId())
            && stored.executionRequestPolicyId().equals(handoff.executionRequestPolicyId())
            && stored.claimId().equals(handoff.claimId())
            && stored.attemptId().equals(handoff.attemptId())
            && stored.grantId().equals(handoff.grantId())
            && stored.proposalFingerprint().equals(handoff.proposalFingerprint())
            && stored.action() == handoff.action()
            && stored.destination().equals(handoff.destination())
            && stored.payloadKind().equals(handoff.payloadKind().name())
            && stored.payloadSha256().equals(handoff.payloadSha256())
            && stored.reconciliationMode() == handoff.reconciliationMode();
        if (!same) {
            throw new IllegalStateException("existing manual handoff record does not match the trusted current handoff");
        }
    }

    public enum RecordState {
        PRESENTED,
        ALREADY_PRESENTED,
        NOT_AVAILABLE
    }

    public record RecordResult(RecordState state, PresentedHandoff handoff, String reason) {
        public RecordResult {
            Objects.requireNonNull(state, "state must not be null");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
            if ((state != RecordState.NOT_AVAILABLE) != (handoff != null)) {
                throw new IllegalArgumentException("available record states must carry exactly one handoff");
            }
        }
    }

    public record PresentedHandoff(
        String handoffId,
        String journalPolicyId,
        String handoffServiceId,
        String capabilityPolicyId,
        String executionRequestPolicyId,
        String claimId,
        String attemptId,
        String grantId,
        String proposalFingerprint,
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        String payloadKind,
        String payloadSha256,
        SleeperManualCounterHandoffService.ReconciliationMode reconciliationMode,
        Instant presentedAt) {
        public PresentedHandoff {
            requireText(handoffId, "handoffId");
            if (!JOURNAL_POLICY_ID.equals(journalPolicyId)) throw new IllegalArgumentException("unexpected journalPolicyId");
            if (!SleeperManualCounterHandoffService.SERVICE_ID.equals(handoffServiceId)) {
                throw new IllegalArgumentException("unexpected handoffServiceId");
            }
            if (!SleeperPlatformCapabilityPolicy.POLICY_ID.equals(capabilityPolicyId)) {
                throw new IllegalArgumentException("unexpected capabilityPolicyId");
            }
            if (!TradeCounterExecutionRequestRepository.REQUEST_POLICY_ID.equals(executionRequestPolicyId)) {
                throw new IllegalArgumentException("unexpected executionRequestPolicyId");
            }
            requireText(claimId, "claimId");
            requireText(attemptId, "attemptId");
            requireText(grantId, "grantId");
            requireFingerprint(proposalFingerprint, "proposalFingerprint");
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            requireText(payloadKind, "payloadKind");
            requireFingerprint(payloadSha256, "payloadSha256");
            Objects.requireNonNull(reconciliationMode, "reconciliationMode must not be null");
            Objects.requireNonNull(presentedAt, "presentedAt must not be null");
        }
    }

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
