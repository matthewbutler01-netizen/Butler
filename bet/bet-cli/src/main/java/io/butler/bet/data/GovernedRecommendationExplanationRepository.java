package io.butler.bet.data;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** BF-653 append-only explanation companion persistence keyed to an immutable BF-627 audit. */
public final class GovernedRecommendationExplanationRepository {
    private final Database database;

    public GovernedRecommendationExplanationRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public CaptureResult capture(ExplanationRecord desired) throws SQLException {
        Objects.requireNonNull(desired, "desired must not be null");
        try (Connection connection = database.openConnection()) {
            ensureTable(connection);
            connection.setAutoCommit(false);
            try {
                Optional<ExplanationRecord> existing = findByAuditId(connection, desired.auditId());
                if (existing.isPresent()) {
                    ExplanationRecord persisted = existing.get();
                    if (!samePayload(persisted, desired)) {
                        throw new IllegalStateException(
                            "BF-653 BLOCKED: immutable explanation companion already exists with different payload");
                    }
                    connection.rollback();
                    return new CaptureResult(CaptureState.ALREADY_CAPTURED_EXACT, persisted);
                }

                String id = desired.id() == null ? UUID.randomUUID().toString() : desired.id();
                ExplanationRecord toPersist = desired.withId(id);
                insert(connection, toPersist);
                ExplanationRecord readback = findByAuditId(connection, toPersist.auditId())
                    .orElseThrow(() -> new SQLException("BF-653 explanation record vanished during transactional readback"));
                if (!samePayload(readback, toPersist)) {
                    throw new SQLException("BF-653 transactional explanation readback reconciliation failed");
                }
                connection.commit();
                return new CaptureResult(CaptureState.CAPTURED_VERIFIED, readback);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /** BF-653 read-only lookup. An absent companion table is a valid not-yet-captured state. */
    public Optional<ExplanationRecord> findByAuditId(String auditId) throws SQLException {
        String normalized = requireText(auditId, "auditId");
        try (Connection connection = database.openConnection()) {
            if (!tableExists(connection)) return Optional.empty();
            return findByAuditId(connection, normalized);
        }
    }

    private static Optional<ExplanationRecord> findByAuditId(Connection connection, String auditId)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT id, audit_id, policy_id, explanation_type, explanation_text,
                evidence_policy_id, evidence_trace, captured_at_utc
            FROM governed_recommendation_explanations
            WHERE audit_id = ?
            """)) {
            statement.setString(1, auditId);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private static void insert(Connection connection, ExplanationRecord value) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO governed_recommendation_explanations(
                id, audit_id, policy_id, explanation_type, explanation_text,
                evidence_policy_id, evidence_trace, captured_at_utc)
            VALUES(?,?,?,?,?,?,?,?)
            """)) {
            statement.setString(1, value.id());
            statement.setString(2, value.auditId());
            statement.setString(3, value.policyId());
            statement.setString(4, value.explanationType());
            statement.setString(5, value.explanationText());
            statement.setString(6, value.evidencePolicyId());
            statement.setString(7, value.evidenceTrace());
            statement.setString(8, value.capturedAtUtc().toString());
            statement.executeUpdate();
        }
    }

    private static ExplanationRecord map(ResultSet rs) throws SQLException {
        return new ExplanationRecord(
            rs.getString("id"),
            rs.getString("audit_id"),
            rs.getString("policy_id"),
            rs.getString("explanation_type"),
            rs.getString("explanation_text"),
            rs.getString("evidence_policy_id"),
            rs.getString("evidence_trace"),
            Instant.parse(rs.getString("captured_at_utc")));
    }

    private static boolean samePayload(ExplanationRecord left, ExplanationRecord right) {
        return left.auditId().equals(right.auditId())
            && left.policyId().equals(right.policyId())
            && left.explanationType().equals(right.explanationType())
            && left.explanationText().equals(right.explanationText())
            && Objects.equals(left.evidencePolicyId(), right.evidencePolicyId())
            && Objects.equals(left.evidenceTrace(), right.evidenceTrace());
    }

    private static boolean tableExists(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='governed_recommendation_explanations'")) {
            try (var rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void ensureTable(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS governed_recommendation_explanations (
                    id TEXT PRIMARY KEY,
                    audit_id TEXT NOT NULL UNIQUE,
                    policy_id TEXT NOT NULL,
                    explanation_type TEXT NOT NULL,
                    explanation_text TEXT NOT NULL,
                    evidence_policy_id TEXT,
                    evidence_trace TEXT,
                    captured_at_utc TEXT NOT NULL,
                    FOREIGN KEY (audit_id) REFERENCES governed_recommendation_audits(id) ON DELETE RESTRICT,
                    CHECK (length(trim(explanation_text)) > 0),
                    CHECK ((evidence_policy_id IS NULL AND evidence_trace IS NULL)
                        OR (evidence_policy_id IS NOT NULL AND evidence_trace IS NOT NULL))
                )
                """);
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_governed_recommendation_explanations_audit "
                    + "ON governed_recommendation_explanations(audit_id)");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public enum CaptureState { CAPTURED_VERIFIED, ALREADY_CAPTURED_EXACT }

    public record CaptureResult(CaptureState state, ExplanationRecord record) {
        public CaptureResult {
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(record, "record must not be null");
        }
    }

    public record ExplanationRecord(
        String id,
        String auditId,
        String policyId,
        String explanationType,
        String explanationText,
        String evidencePolicyId,
        String evidenceTrace,
        Instant capturedAtUtc) {
        public ExplanationRecord {
            id = optional(id);
            auditId = requireText(auditId, "auditId");
            policyId = requireText(policyId, "policyId");
            explanationType = requireText(explanationType, "explanationType");
            explanationText = requireText(explanationText, "explanationText");
            evidencePolicyId = optional(evidencePolicyId);
            evidenceTrace = optional(evidenceTrace);
            if ((evidencePolicyId == null) != (evidenceTrace == null)) {
                throw new IllegalArgumentException("evidencePolicyId and evidenceTrace must both be present or absent");
            }
            Objects.requireNonNull(capturedAtUtc, "capturedAtUtc must not be null");
        }

        public ExplanationRecord withId(String newId) {
            return new ExplanationRecord(newId, auditId, policyId, explanationType, explanationText,
                evidencePolicyId, evidenceTrace, capturedAtUtc);
        }
    }
}
