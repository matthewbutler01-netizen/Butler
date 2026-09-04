package io.butler.bet.integration.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionRequestRepository;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;

import java.sql.SQLException;
import java.util.Objects;

/**
 * Presents one trusted authorized counter payload for manual Sleeper action.
 * Presentation is not dispatch, success, reconciliation, or grant consumption.
 */
public final class SleeperManualCounterHandoffService {
    public static final String SERVICE_ID =
        "sleeper-manual-counter-handoff-v1-trusted-claim-present-only";

    private final TradeCounterExecutionRequestRepository requests;

    public SleeperManualCounterHandoffService(Database database) {
        this.requests = new TradeCounterExecutionRequestRepository(
            Objects.requireNonNull(database, "database must not be null"));
    }

    public Result prepare(String claimId) throws SQLException {
        claimId = requireText(claimId, "claimId");
        var request = requests.findByClaimId(claimId).orElse(null);
        if (request == null) {
            return new Result(
                SERVICE_ID,
                State.NOT_AVAILABLE,
                null,
                "Trusted IN_FLIGHT execution request was not found for the supplied claim id.");
        }

        var capability = SleeperPlatformCapabilityPolicy.assess(request.action());
        if (capability.writeCapability()
                != SleeperPlatformCapabilityPolicy.WriteCapability.UNSUPPORTED_OFFICIAL_API
            || capability.executionChannel()
                != SleeperPlatformCapabilityPolicy.ExecutionChannel.MANUAL_HANDOFF_REQUIRED) {
            throw new IllegalStateException(
                "Sleeper manual handoff requires the governed official-read-only capability contract");
        }

        ReconciliationMode reconciliationMode = switch (request.action()) {
            case SEND_NEGOTIATION_MESSAGE -> ReconciliationMode.NO_OFFICIAL_READBACK;
            case SUBMIT_COUNTER_TRADE -> ReconciliationMode.SLEEPER_TRANSACTION_READBACK;
        };

        var handoff = new Handoff(
            SERVICE_ID,
            capability.policyId(),
            request.requestPolicyId(),
            request.claimId(),
            request.attemptId(),
            request.grantId(),
            request.proposalFingerprint(),
            request.action(),
            request.destination(),
            request.payloadKind(),
            request.payloadText(),
            request.payloadSha256(),
            reconciliationMode,
            "Manual Sleeper action required. This artifact only presents the exact authorized payload; it does not prove that the user sent or submitted it.");

        return new Result(
            SERVICE_ID,
            State.HANDOFF_READY,
            handoff,
            "Exact trusted counter payload is ready for manual Sleeper handoff.");
    }

    public enum State {
        HANDOFF_READY,
        NOT_AVAILABLE
    }

    public enum ReconciliationMode {
        NO_OFFICIAL_READBACK,
        SLEEPER_TRANSACTION_READBACK
    }

    public record Handoff(
        String serviceId,
        String capabilityPolicyId,
        String executionRequestPolicyId,
        String claimId,
        String attemptId,
        String grantId,
        String proposalFingerprint,
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        TradeCounterExecutionAttemptRepository.PayloadKind payloadKind,
        String payloadText,
        String payloadSha256,
        ReconciliationMode reconciliationMode,
        String warning) {
        public Handoff {
            if (!SERVICE_ID.equals(serviceId)) throw new IllegalArgumentException("unexpected serviceId");
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
            Objects.requireNonNull(payloadKind, "payloadKind must not be null");
            if (payloadText == null || payloadText.isEmpty()) {
                throw new IllegalArgumentException("payloadText must not be empty");
            }
            requireFingerprint(payloadSha256, "payloadSha256");
            Objects.requireNonNull(reconciliationMode, "reconciliationMode must not be null");
            requireText(warning, "warning");

            boolean compatible = switch (action) {
                case SEND_NEGOTIATION_MESSAGE ->
                    destination.type() == TradeCounterAuthorizationPolicy.DestinationType.MANAGER
                        && payloadKind == TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT
                        && reconciliationMode == ReconciliationMode.NO_OFFICIAL_READBACK;
                case SUBMIT_COUNTER_TRADE ->
                    destination.type() == TradeCounterAuthorizationPolicy.DestinationType.LEAGUE
                        && payloadKind == TradeCounterExecutionAttemptRepository.PayloadKind.COUNTER_TRADE_REQUEST_JSON
                        && reconciliationMode == ReconciliationMode.SLEEPER_TRANSACTION_READBACK;
            };
            if (!compatible) throw new IllegalArgumentException("manual handoff coordinates are incompatible");
        }
    }

    public record Result(String serviceId, State state, Handoff handoff, String reason) {
        public Result {
            if (!SERVICE_ID.equals(serviceId)) throw new IllegalArgumentException("unexpected serviceId");
            Objects.requireNonNull(state, "state must not be null");
            requireText(reason, "reason");
            if ((state == State.HANDOFF_READY) != (handoff != null)) {
                throw new IllegalArgumentException("HANDOFF_READY must carry exactly one handoff artifact");
            }
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
