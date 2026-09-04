package io.butler.bet.integration.sleeper;

import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;

import java.util.Objects;

/**
 * Governed capability boundary for the official Sleeper API.
 * Sleeper's documented public API is read-only; counter writes require manual handoff.
 */
public final class SleeperPlatformCapabilityPolicy {
    public static final String POLICY_ID =
        "sleeper-platform-capability-v1-official-read-only-manual-write-handoff";
    public static final String OFFICIAL_API_BASE = "https://api.sleeper.app/v1";

    private SleeperPlatformCapabilityPolicy() {}

    public static Capability assess(TradeCounterAuthorizationPolicy.Action action) {
        Objects.requireNonNull(action, "action must not be null");
        return switch (action) {
            case SEND_NEGOTIATION_MESSAGE -> new Capability(
                POLICY_ID,
                action,
                WriteCapability.UNSUPPORTED_OFFICIAL_API,
                ExecutionChannel.MANUAL_HANDOFF_REQUIRED,
                ReadReconciliationCapability.NOT_AVAILABLE,
                "Sleeper's official public API is read-only and does not provide a supported message-send endpoint.");
            case SUBMIT_COUNTER_TRADE -> new Capability(
                POLICY_ID,
                action,
                WriteCapability.UNSUPPORTED_OFFICIAL_API,
                ExecutionChannel.MANUAL_HANDOFF_REQUIRED,
                ReadReconciliationCapability.TRANSACTIONS_SUPPORTED,
                "Sleeper's official public API is read-only; counter trades must be submitted manually, but league transactions can be read afterward for reconciliation.");
        };
    }

    public enum WriteCapability {
        UNSUPPORTED_OFFICIAL_API
    }

    public enum ExecutionChannel {
        MANUAL_HANDOFF_REQUIRED
    }

    public enum ReadReconciliationCapability {
        NOT_AVAILABLE,
        TRANSACTIONS_SUPPORTED
    }

    public record Capability(
        String policyId,
        TradeCounterAuthorizationPolicy.Action action,
        WriteCapability writeCapability,
        ExecutionChannel executionChannel,
        ReadReconciliationCapability readReconciliationCapability,
        String reason) {
        public Capability {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(writeCapability, "writeCapability must not be null");
            Objects.requireNonNull(executionChannel, "executionChannel must not be null");
            Objects.requireNonNull(readReconciliationCapability,
                "readReconciliationCapability must not be null");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
            if (writeCapability != WriteCapability.UNSUPPORTED_OFFICIAL_API
                || executionChannel != ExecutionChannel.MANUAL_HANDOFF_REQUIRED) {
                throw new IllegalArgumentException("Sleeper official write execution must remain unsupported/manual");
            }
            if (action == TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE
                && readReconciliationCapability != ReadReconciliationCapability.NOT_AVAILABLE) {
                throw new IllegalArgumentException("message send has no official read reconciliation contract");
            }
            if (action == TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE
                && readReconciliationCapability != ReadReconciliationCapability.TRANSACTIONS_SUPPORTED) {
                throw new IllegalArgumentException("trade submission must expose transaction reconciliation support");
            }
        }
    }
}
