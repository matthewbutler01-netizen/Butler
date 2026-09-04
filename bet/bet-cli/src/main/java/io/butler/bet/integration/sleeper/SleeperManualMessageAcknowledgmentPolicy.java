package io.butler.bet.integration.sleeper;

import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;

import java.time.Instant;
import java.util.Objects;

/**
 * Pure governance policy for explicit user acknowledgment that one exact manual Sleeper
 * negotiation message was sent. This policy never mutates local state or performs a platform action.
 */
public final class SleeperManualMessageAcknowledgmentPolicy {
    public static final String POLICY_ID =
        "sleeper-manual-message-acknowledgment-v1-explicit-handoff-payload-confirmation";
    public static final String REQUIRED_CONFIRMATION = "SENT_EXACT_MESSAGE";

    private SleeperManualMessageAcknowledgmentPolicy() {}

    public static Decision acknowledge(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        AcknowledgmentRequest request) {
        Objects.requireNonNull(handoff, "handoff must not be null");
        Objects.requireNonNull(request, "request must not be null");

        if (!acknowledgeableMessageHandoff(handoff)) {
            return decision(
                handoff,
                request,
                State.INCONCLUSIVE,
                ReasonCode.HANDOFF_NOT_MANUAL_MESSAGE,
                LocalCompletionEligibility.NONE,
                null,
                "Only a presented SEND_NEGOTIATION_MESSAGE handoff with NO_OFFICIAL_READBACK can use manual message acknowledgment.");
        }

        if (!handoff.grantId().equals(request.grantId())
            || !handoff.handoffId().equals(request.handoffId())
            || !handoff.payloadSha256().equals(request.payloadSha256())) {
            return decision(
                handoff,
                request,
                State.INCONCLUSIVE,
                ReasonCode.ACKNOWLEDGMENT_COORDINATES_MISMATCH,
                LocalCompletionEligibility.NONE,
                null,
                "Acknowledgment grant, handoff, or payload hash does not match the trusted presented message.");
        }

        if (request.acknowledgedAt().isBefore(handoff.presentedAt())) {
            return decision(
                handoff,
                request,
                State.INCONCLUSIVE,
                ReasonCode.ACKNOWLEDGMENT_PREDATES_HANDOFF,
                LocalCompletionEligibility.NONE,
                null,
                "Manual-message acknowledgment cannot predate the first trusted handoff presentation.");
        }

        if (!REQUIRED_CONFIRMATION.equals(request.confirmation())) {
            return decision(
                handoff,
                request,
                State.NOT_ACKNOWLEDGED,
                ReasonCode.CONFIRMATION_NOT_EXACT,
                LocalCompletionEligibility.NONE,
                null,
                "Explicit confirmation phrase did not exactly acknowledge sending the bound message payload.");
        }

        return decision(
            handoff,
            request,
            State.ACKNOWLEDGED,
            ReasonCode.EXACT_MANUAL_MESSAGE_SENT_ACKNOWLEDGED,
            LocalCompletionEligibility.MANUAL_MESSAGE_SUCCESS,
            request.acknowledgedAt(),
            "User explicitly acknowledged sending the exact trusted manual negotiation-message payload.");
    }

    private static boolean acknowledgeableMessageHandoff(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff) {
        return handoff.action() == TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE
            && handoff.destination().type() == TradeCounterAuthorizationPolicy.DestinationType.MANAGER
            && TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT.name()
                .equals(handoff.payloadKind())
            && handoff.reconciliationMode()
                == SleeperManualCounterHandoffService.ReconciliationMode.NO_OFFICIAL_READBACK;
    }

    private static Decision decision(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        AcknowledgmentRequest request,
        State state,
        ReasonCode reasonCode,
        LocalCompletionEligibility eligibility,
        Instant acknowledgedAt,
        String reason) {
        return new Decision(
            POLICY_ID,
            SleeperManualCounterHandoffRepository.JOURNAL_POLICY_ID,
            SleeperManualCounterHandoffService.SERVICE_ID,
            handoff.claimId(),
            handoff.attemptId(),
            handoff.grantId(),
            handoff.handoffId(),
            handoff.payloadSha256(),
            handoff.destination(),
            handoff.presentedAt(),
            request.confirmation(),
            state,
            reasonCode,
            eligibility,
            acknowledgedAt,
            reason);
    }

    public enum State {
        ACKNOWLEDGED,
        NOT_ACKNOWLEDGED,
        INCONCLUSIVE
    }

    public enum ReasonCode {
        EXACT_MANUAL_MESSAGE_SENT_ACKNOWLEDGED,
        CONFIRMATION_NOT_EXACT,
        ACKNOWLEDGMENT_COORDINATES_MISMATCH,
        ACKNOWLEDGMENT_PREDATES_HANDOFF,
        HANDOFF_NOT_MANUAL_MESSAGE
    }

    public enum LocalCompletionEligibility {
        MANUAL_MESSAGE_SUCCESS,
        NONE
    }

    public record AcknowledgmentRequest(
        String grantId,
        String handoffId,
        String payloadSha256,
        String confirmation,
        Instant acknowledgedAt) {
        public AcknowledgmentRequest {
            grantId = requireText(grantId, "grantId");
            handoffId = requireText(handoffId, "handoffId");
            requireFingerprint(payloadSha256, "payloadSha256");
            confirmation = requireText(confirmation, "confirmation");
            Objects.requireNonNull(acknowledgedAt, "acknowledgedAt must not be null");
        }
    }

    public record Decision(
        String policyId,
        String handoffJournalPolicyId,
        String handoffServiceId,
        String claimId,
        String attemptId,
        String grantId,
        String handoffId,
        String payloadSha256,
        TradeCounterAuthorizationPolicy.Destination destination,
        Instant presentedAt,
        String suppliedConfirmation,
        State state,
        ReasonCode reasonCode,
        LocalCompletionEligibility localCompletionEligibility,
        Instant acknowledgedAt,
        String reason) {
        public Decision {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
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
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.requireNonNull(presentedAt, "presentedAt must not be null");
            suppliedConfirmation = requireText(suppliedConfirmation, "suppliedConfirmation");
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(reasonCode, "reasonCode must not be null");
            Objects.requireNonNull(localCompletionEligibility, "localCompletionEligibility must not be null");
            reason = requireText(reason, "reason");

            if (state == State.ACKNOWLEDGED) {
                if (reasonCode != ReasonCode.EXACT_MANUAL_MESSAGE_SENT_ACKNOWLEDGED
                    || localCompletionEligibility != LocalCompletionEligibility.MANUAL_MESSAGE_SUCCESS
                    || acknowledgedAt == null
                    || !REQUIRED_CONFIRMATION.equals(suppliedConfirmation)
                    || acknowledgedAt.isBefore(presentedAt)) {
                    throw new IllegalArgumentException("invalid acknowledged manual-message decision");
                }
            } else {
                if (localCompletionEligibility != LocalCompletionEligibility.NONE
                    || acknowledgedAt != null) {
                    throw new IllegalArgumentException("non-acknowledged decision cannot be completion eligible");
                }
                if (state == State.NOT_ACKNOWLEDGED
                    && reasonCode != ReasonCode.CONFIRMATION_NOT_EXACT) {
                    throw new IllegalArgumentException("NOT_ACKNOWLEDGED requires inexact confirmation");
                }
                if (state == State.INCONCLUSIVE
                    && (reasonCode == ReasonCode.EXACT_MANUAL_MESSAGE_SENT_ACKNOWLEDGED
                        || reasonCode == ReasonCode.CONFIRMATION_NOT_EXACT)) {
                    throw new IllegalArgumentException("INCONCLUSIVE requires incompatible or mismatched evidence");
                }
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
