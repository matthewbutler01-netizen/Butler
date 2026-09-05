package io.butler.bet.integration.sleeper;

import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.execution.TradeCounterExecutionOutcomePolicy;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;

import java.time.Instant;
import java.util.Objects;

/**
 * Pure governance policy for explicit human evidence that one exact presented manual Sleeper
 * handoff was not acted on externally. This policy never mutates storage or performs a platform
 * action. A confirmed no-action decision is eligible only for local FAILED + one-shot grant close,
 * matching the existing definite-no-action execution outcome semantics.
 */
public final class SleeperManualCounterNoActionAcknowledgmentPolicy {
    public static final String POLICY_ID =
        "sleeper-manual-counter-no-action-acknowledgment-v1-explicit-handoff-payload-confirmation";
    public static final String REQUIRED_CONFIRMATION = "NO_EXTERNAL_ACTION_TAKEN";

    private SleeperManualCounterNoActionAcknowledgmentPolicy() {}

    public static Decision acknowledge(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        AcknowledgmentRequest request) {
        Objects.requireNonNull(handoff, "handoff must not be null");
        Objects.requireNonNull(request, "request must not be null");

        if (!acknowledgeableManualHandoff(handoff)) {
            return decision(
                handoff,
                request,
                State.INCONCLUSIVE,
                ReasonCode.HANDOFF_NOT_SUPPORTED_MANUAL_ACTION,
                LocalTerminalEligibility.NONE,
                null,
                TradeCounterExecutionOutcomePolicy.GrantDisposition.RETAIN_ACTIVE,
                null,
                "Only a supported presented manual message or manual trade handoff can carry no-action acknowledgment evidence.");
        }

        if (!handoff.grantId().equals(request.grantId())
            || !handoff.handoffId().equals(request.handoffId())
            || !handoff.payloadSha256().equals(request.payloadSha256())) {
            return decision(
                handoff,
                request,
                State.INCONCLUSIVE,
                ReasonCode.ACKNOWLEDGMENT_COORDINATES_MISMATCH,
                LocalTerminalEligibility.NONE,
                null,
                TradeCounterExecutionOutcomePolicy.GrantDisposition.RETAIN_ACTIVE,
                null,
                "No-action acknowledgment grant, handoff, or payload hash does not match the trusted presented action.");
        }

        if (request.acknowledgedAt().isBefore(handoff.presentedAt())) {
            return decision(
                handoff,
                request,
                State.INCONCLUSIVE,
                ReasonCode.ACKNOWLEDGMENT_PREDATES_HANDOFF,
                LocalTerminalEligibility.NONE,
                null,
                TradeCounterExecutionOutcomePolicy.GrantDisposition.RETAIN_ACTIVE,
                null,
                "No-action acknowledgment cannot predate the first trusted handoff presentation.");
        }

        if (!REQUIRED_CONFIRMATION.equals(request.confirmation())) {
            return decision(
                handoff,
                request,
                State.NOT_ACKNOWLEDGED,
                ReasonCode.CONFIRMATION_NOT_EXACT,
                LocalTerminalEligibility.NONE,
                null,
                TradeCounterExecutionOutcomePolicy.GrantDisposition.RETAIN_ACTIVE,
                null,
                "Explicit confirmation phrase did not exactly attest that no external action was taken for the bound handoff.");
        }

        return decision(
            handoff,
            request,
            State.ACKNOWLEDGED,
            ReasonCode.EXACT_NO_EXTERNAL_ACTION_ACKNOWLEDGED,
            LocalTerminalEligibility.CONFIRMED_NO_ACTION_FAILURE,
            TradeCounterExecutionAttemptRepository.State.FAILED,
            TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME,
            request.acknowledgedAt(),
            "User explicitly acknowledged that the exact trusted manual handoff was not acted on externally; local FAILED plus closure of the one-shot authorization is eligible so any retry requires fresh explicit authorization.");
    }

    private static boolean acknowledgeableManualHandoff(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff) {
        boolean message = handoff.action() == TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE
            && handoff.destination().type() == TradeCounterAuthorizationPolicy.DestinationType.MANAGER
            && TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT.name()
                .equals(handoff.payloadKind())
            && handoff.reconciliationMode()
                == SleeperManualCounterHandoffService.ReconciliationMode.NO_OFFICIAL_READBACK;
        boolean trade = handoff.action() == TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE
            && handoff.destination().type() == TradeCounterAuthorizationPolicy.DestinationType.LEAGUE
            && TradeCounterExecutionAttemptRepository.PayloadKind.COUNTER_TRADE_REQUEST_JSON.name()
                .equals(handoff.payloadKind())
            && handoff.reconciliationMode()
                == SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK;
        return message || trade;
    }

    private static Decision decision(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        AcknowledgmentRequest request,
        State state,
        ReasonCode reasonCode,
        LocalTerminalEligibility eligibility,
        TradeCounterExecutionAttemptRepository.State attemptTerminalState,
        TradeCounterExecutionOutcomePolicy.GrantDisposition grantDisposition,
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
            handoff.action(),
            handoff.destination(),
            handoff.presentedAt(),
            request.confirmation(),
            state,
            reasonCode,
            eligibility,
            attemptTerminalState,
            grantDisposition,
            acknowledgedAt,
            reason);
    }

    public enum State {
        ACKNOWLEDGED,
        NOT_ACKNOWLEDGED,
        INCONCLUSIVE
    }

    public enum ReasonCode {
        EXACT_NO_EXTERNAL_ACTION_ACKNOWLEDGED,
        CONFIRMATION_NOT_EXACT,
        ACKNOWLEDGMENT_COORDINATES_MISMATCH,
        ACKNOWLEDGMENT_PREDATES_HANDOFF,
        HANDOFF_NOT_SUPPORTED_MANUAL_ACTION
    }

    public enum LocalTerminalEligibility {
        CONFIRMED_NO_ACTION_FAILURE,
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
            confirmation = requireRawConfirmation(confirmation);
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
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        Instant presentedAt,
        String suppliedConfirmation,
        State state,
        ReasonCode reasonCode,
        LocalTerminalEligibility localTerminalEligibility,
        TradeCounterExecutionAttemptRepository.State attemptTerminalState,
        TradeCounterExecutionOutcomePolicy.GrantDisposition grantDisposition,
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
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.requireNonNull(presentedAt, "presentedAt must not be null");
            suppliedConfirmation = requireRawConfirmation(suppliedConfirmation);
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(reasonCode, "reasonCode must not be null");
            Objects.requireNonNull(localTerminalEligibility, "localTerminalEligibility must not be null");
            Objects.requireNonNull(grantDisposition, "grantDisposition must not be null");
            reason = requireText(reason, "reason");

            if (state == State.ACKNOWLEDGED) {
                if (reasonCode != ReasonCode.EXACT_NO_EXTERNAL_ACTION_ACKNOWLEDGED
                    || localTerminalEligibility != LocalTerminalEligibility.CONFIRMED_NO_ACTION_FAILURE
                    || attemptTerminalState != TradeCounterExecutionAttemptRepository.State.FAILED
                    || grantDisposition != TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME
                    || acknowledgedAt == null
                    || !REQUIRED_CONFIRMATION.equals(suppliedConfirmation)
                    || acknowledgedAt.isBefore(presentedAt)) {
                    throw new IllegalArgumentException("invalid acknowledged manual no-action decision");
                }
            } else {
                if (localTerminalEligibility != LocalTerminalEligibility.NONE
                    || attemptTerminalState != null
                    || grantDisposition != TradeCounterExecutionOutcomePolicy.GrantDisposition.RETAIN_ACTIVE
                    || acknowledgedAt != null) {
                    throw new IllegalArgumentException(
                        "non-acknowledged no-action decision cannot be terminal or close authorization");
                }
                if (state == State.NOT_ACKNOWLEDGED
                    && reasonCode != ReasonCode.CONFIRMATION_NOT_EXACT) {
                    throw new IllegalArgumentException("NOT_ACKNOWLEDGED requires inexact confirmation");
                }
                if (state == State.INCONCLUSIVE
                    && (reasonCode == ReasonCode.EXACT_NO_EXTERNAL_ACTION_ACKNOWLEDGED
                        || reasonCode == ReasonCode.CONFIRMATION_NOT_EXACT)) {
                    throw new IllegalArgumentException(
                        "INCONCLUSIVE requires incompatible or mismatched evidence");
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

    private static String requireRawConfirmation(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("confirmation must not be blank");
        }
        return value;
    }
}
