package io.butler.bet.data;

import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Loads one executable-shaped request strictly from persisted BF-392/BF-393 trusted state.
 * This repository never performs the requested external action.
 */
public final class TradeCounterExecutionRequestRepository {
    public static final String REQUEST_POLICY_ID =
        "trade-counter-execution-request-v1-persisted-claim-attempt-only";

    private final Database database;

    public TradeCounterExecutionRequestRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public void initialize() throws SQLException {
        new TradeCounterExecutionClaimRepository(database).initialize();
    }

    public Optional<ExecutionRequest> findByClaimId(String claimId) throws SQLException {
        claimId = requireText(claimId, "claimId");
        initialize();
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 SELECT
                     c.claim_id,
                     c.claim_policy_id,
                     c.attempt_id,
                     c.grant_id,
                     c.proposal_fingerprint,
                     c.action,
                     c.destination_type,
                     c.destination_id,
                     a.payload_kind,
                     a.payload_text,
                     a.payload_sha256,
                     a.state,
                     g.consumed_at
                 FROM trade_counter_execution_claims c
                 JOIN trade_counter_execution_attempts a ON a.attempt_id = c.attempt_id
                 JOIN trade_counter_authorization_grants g ON g.grant_id = c.grant_id
                 WHERE c.claim_id = ?
                 """)) {
            statement.setString(1, claimId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readAndValidate(rs));
            }
        }
    }

    private static ExecutionRequest readAndValidate(ResultSet rs) throws SQLException {
        if (!TradeCounterExecutionClaimRepository.CLAIM_POLICY_ID.equals(rs.getString("claim_policy_id"))) {
            throw new IllegalStateException("execution request requires governed BF-393 claim policy");
        }
        if (!TradeCounterExecutionAttemptRepository.State.IN_FLIGHT.name().equals(rs.getString("state"))) {
            throw new IllegalStateException("execution request requires IN_FLIGHT attempt");
        }
        if (rs.getString("consumed_at") != null) {
            throw new IllegalStateException("execution request requires active unconsumed authorization grant");
        }

        var action = TradeCounterAuthorizationPolicy.Action.valueOf(rs.getString("action"));
        var destination = new TradeCounterAuthorizationPolicy.Destination(
            TradeCounterAuthorizationPolicy.DestinationType.valueOf(rs.getString("destination_type")),
            rs.getString("destination_id"));
        var payloadKind = TradeCounterExecutionAttemptRepository.PayloadKind.valueOf(
            rs.getString("payload_kind"));
        String payloadText = rs.getString("payload_text");
        String payloadSha256 = rs.getString("payload_sha256");

        return new ExecutionRequest(
            REQUEST_POLICY_ID,
            rs.getString("claim_id"),
            rs.getString("attempt_id"),
            rs.getString("grant_id"),
            rs.getString("proposal_fingerprint"),
            action,
            destination,
            payloadKind,
            payloadText,
            payloadSha256);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance(TradeCounterExecutionAttemptRepository.PAYLOAD_HASH_ALGORITHM)
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                TradeCounterExecutionAttemptRepository.PAYLOAD_HASH_ALGORITHM + " is unavailable", e);
        }
    }

    private static void requireFingerprint(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public record ExecutionRequest(
        String requestPolicyId,
        String claimId,
        String attemptId,
        String grantId,
        String proposalFingerprint,
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        TradeCounterExecutionAttemptRepository.PayloadKind payloadKind,
        String payloadText,
        String payloadSha256) {
        public ExecutionRequest {
            if (!REQUEST_POLICY_ID.equals(requestPolicyId)) {
                throw new IllegalArgumentException("unexpected requestPolicyId");
            }
            requireText(claimId, "claimId");
            requireText(attemptId, "attemptId");
            requireText(grantId, "grantId");
            requireFingerprint(proposalFingerprint, "proposalFingerprint");
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.requireNonNull(payloadKind, "payloadKind must not be null");
            if (payloadText == null || payloadText.isEmpty()) {
                throw new IllegalArgumentException("payloadText must not be empty");
            }
            requireFingerprint(payloadSha256, "payloadSha256");
            if (!sha256(payloadText).equals(payloadSha256)) {
                throw new IllegalArgumentException("payload hash does not match exact persisted payload bytes");
            }
            boolean compatible = switch (action) {
                case SEND_NEGOTIATION_MESSAGE ->
                    destination.type() == TradeCounterAuthorizationPolicy.DestinationType.MANAGER
                        && payloadKind == TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT;
                case SUBMIT_COUNTER_TRADE ->
                    destination.type() == TradeCounterAuthorizationPolicy.DestinationType.LEAGUE
                        && payloadKind == TradeCounterExecutionAttemptRepository.PayloadKind.COUNTER_TRADE_REQUEST_JSON;
            };
            if (!compatible) {
                throw new IllegalArgumentException("persisted action, destination, and payload kind are incompatible");
            }
        }
    }
}
