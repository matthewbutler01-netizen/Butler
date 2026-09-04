package io.butler.bet.execution;

import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionRequestRepository;

import java.util.Objects;

/**
 * Pure governance policy for reconciling executor results with the trusted execution request.
 * It emits durable-state directives only; it never mutates storage or performs an external action.
 */
public final class TradeCounterExecutionOutcomePolicy {
    public static final String POLICY_ID =
        "trade-counter-execution-outcome-v1-live-terminal-no-retry-unknown-reconcile";

    private TradeCounterExecutionOutcomePolicy() {}

    public static Directive classify(
        TradeCounterExecutionRequestRepository.ExecutionRequest request,
        TradeCounterActionExecutor.ExecutionResult result) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(result, "result must not be null");
        requireMatchingExecution(request, result);
        requireModeStateCompatibility(result);

        if (result.mode() == TradeCounterActionExecutor.Mode.DRY_RUN) {
            return directive(
                request,
                result,
                OutcomeState.DRY_RUN_NO_MUTATION,
                null,
                GrantDisposition.RETAIN_ACTIVE,
                false,
                "Dry-run confirmation is non-executing evidence and must not mutate the attempt or authorization grant.");
        }

        return switch (result.state()) {
            case DISPATCHED -> directive(
                request,
                result,
                OutcomeState.CONFIRMED_SUCCESS,
                TradeCounterExecutionAttemptRepository.State.SUCCEEDED,
                GrantDisposition.CONSUME,
                false,
                "Live executor reported affirmative platform acceptance; finalize the attempt as SUCCEEDED and consume the one-shot authorization.");
            case DEFINITE_FAILURE -> directive(
                request,
                result,
                OutcomeState.CONFIRMED_NO_ACTION_FAILURE,
                TradeCounterExecutionAttemptRepository.State.FAILED,
                GrantDisposition.CONSUME,
                false,
                "Live executor proved no external action occurred; finalize FAILED and close the one-shot authorization so any retry requires fresh explicit authorization.");
            case UNKNOWN -> directive(
                request,
                result,
                OutcomeState.UNKNOWN_PENDING_RECONCILIATION,
                TradeCounterExecutionAttemptRepository.State.UNKNOWN,
                GrantDisposition.RETAIN_ACTIVE,
                true,
                "Remote outcome is unknown; finalize the attempt as UNKNOWN, retain the grant as an active retry lock, and require reconciliation before any new authorization or execution.");
            case DRY_RUN_CONFIRMED -> throw new IllegalArgumentException(
                "LIVE mode cannot report DRY_RUN_CONFIRMED");
        };
    }

    public static UnknownResolutionDirective reconcileUnknown(
        Directive unknownDirective,
        UnknownResolution resolution,
        String evidenceDetail) {
        Objects.requireNonNull(unknownDirective, "unknownDirective must not be null");
        Objects.requireNonNull(resolution, "resolution must not be null");
        if (unknownDirective.state() != OutcomeState.UNKNOWN_PENDING_RECONCILIATION
            || !unknownDirective.reconciliationRequired()
            || unknownDirective.attemptTerminalState() != TradeCounterExecutionAttemptRepository.State.UNKNOWN
            || unknownDirective.grantDisposition() != GrantDisposition.RETAIN_ACTIVE) {
            throw new IllegalArgumentException(
                "unknown reconciliation requires an UNKNOWN_PENDING_RECONCILIATION directive");
        }
        if (evidenceDetail == null || evidenceDetail.isBlank()) {
            throw new IllegalArgumentException("reconciliation evidenceDetail must not be blank");
        }

        return switch (resolution) {
            case REMOTE_ACTION_CONFIRMED -> new UnknownResolutionDirective(
                POLICY_ID,
                unknownDirective.claimId(),
                unknownDirective.attemptId(),
                unknownDirective.grantId(),
                resolution,
                GrantDisposition.CONSUME,
                true,
                evidenceDetail.trim(),
                "Unknown outcome was reconciled as remote success; consume the one-shot authorization. The historical attempt remains UNKNOWN for audit integrity.");
            case REMOTE_NO_ACTION_CONFIRMED -> new UnknownResolutionDirective(
                POLICY_ID,
                unknownDirective.claimId(),
                unknownDirective.attemptId(),
                unknownDirective.grantId(),
                resolution,
                GrantDisposition.CONSUME,
                false,
                evidenceDetail.trim(),
                "Unknown outcome was reconciled as no remote action; consume/close the old authorization so any retry requires a fresh explicit authorization. The historical attempt remains UNKNOWN.");
        };
    }

    private static void requireMatchingExecution(
        TradeCounterExecutionRequestRepository.ExecutionRequest request,
        TradeCounterActionExecutor.ExecutionResult result) {
        if (!request.claimId().equals(result.claimId())
            || !request.attemptId().equals(result.attemptId())
            || !request.grantId().equals(result.grantId())
            || !request.payloadSha256().equals(result.payloadSha256())) {
            throw new IllegalArgumentException(
                "executor result does not match the trusted persisted execution request");
        }
    }

    private static void requireModeStateCompatibility(
        TradeCounterActionExecutor.ExecutionResult result) {
        if (result.mode() == TradeCounterActionExecutor.Mode.DRY_RUN
            && result.state() != TradeCounterActionExecutor.State.DRY_RUN_CONFIRMED) {
            throw new IllegalArgumentException("DRY_RUN mode requires DRY_RUN_CONFIRMED result");
        }
        if (result.mode() == TradeCounterActionExecutor.Mode.LIVE
            && result.state() == TradeCounterActionExecutor.State.DRY_RUN_CONFIRMED) {
            throw new IllegalArgumentException("LIVE mode cannot report DRY_RUN_CONFIRMED");
        }
    }

    private static Directive directive(
        TradeCounterExecutionRequestRepository.ExecutionRequest request,
        TradeCounterActionExecutor.ExecutionResult result,
        OutcomeState state,
        TradeCounterExecutionAttemptRepository.State attemptTerminalState,
        GrantDisposition grantDisposition,
        boolean reconciliationRequired,
        String reason) {
        return new Directive(
            POLICY_ID,
            request.claimId(),
            request.attemptId(),
            request.grantId(),
            request.payloadSha256(),
            result.executorId(),
            result.mode(),
            result.state(),
            state,
            attemptTerminalState,
            grantDisposition,
            reconciliationRequired,
            result.detail(),
            reason);
    }

    public enum OutcomeState {
        DRY_RUN_NO_MUTATION,
        CONFIRMED_SUCCESS,
        CONFIRMED_NO_ACTION_FAILURE,
        UNKNOWN_PENDING_RECONCILIATION
    }

    public enum GrantDisposition {
        RETAIN_ACTIVE,
        CONSUME
    }

    public enum UnknownResolution {
        REMOTE_ACTION_CONFIRMED,
        REMOTE_NO_ACTION_CONFIRMED
    }

    public record Directive(
        String policyId,
        String claimId,
        String attemptId,
        String grantId,
        String payloadSha256,
        String executorId,
        TradeCounterActionExecutor.Mode executorMode,
        TradeCounterActionExecutor.State executorState,
        OutcomeState state,
        TradeCounterExecutionAttemptRepository.State attemptTerminalState,
        GrantDisposition grantDisposition,
        boolean reconciliationRequired,
        String executorDetail,
        String reason) {
        public Directive {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            requireText(claimId, "claimId");
            requireText(attemptId, "attemptId");
            requireText(grantId, "grantId");
            requireFingerprint(payloadSha256, "payloadSha256");
            requireText(executorId, "executorId");
            Objects.requireNonNull(executorMode, "executorMode must not be null");
            Objects.requireNonNull(executorState, "executorState must not be null");
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(grantDisposition, "grantDisposition must not be null");
            requireText(executorDetail, "executorDetail");
            requireText(reason, "reason");

            switch (state) {
                case DRY_RUN_NO_MUTATION -> {
                    if (executorMode != TradeCounterActionExecutor.Mode.DRY_RUN
                        || executorState != TradeCounterActionExecutor.State.DRY_RUN_CONFIRMED
                        || attemptTerminalState != null
                        || grantDisposition != GrantDisposition.RETAIN_ACTIVE
                        || reconciliationRequired) {
                        throw new IllegalArgumentException("invalid dry-run reconciliation directive");
                    }
                }
                case CONFIRMED_SUCCESS -> requireLiveTerminal(
                    executorMode, executorState, TradeCounterActionExecutor.State.DISPATCHED,
                    attemptTerminalState, TradeCounterExecutionAttemptRepository.State.SUCCEEDED,
                    grantDisposition, reconciliationRequired);
                case CONFIRMED_NO_ACTION_FAILURE -> requireLiveTerminal(
                    executorMode, executorState, TradeCounterActionExecutor.State.DEFINITE_FAILURE,
                    attemptTerminalState, TradeCounterExecutionAttemptRepository.State.FAILED,
                    grantDisposition, reconciliationRequired);
                case UNKNOWN_PENDING_RECONCILIATION -> {
                    if (executorMode != TradeCounterActionExecutor.Mode.LIVE
                        || executorState != TradeCounterActionExecutor.State.UNKNOWN
                        || attemptTerminalState != TradeCounterExecutionAttemptRepository.State.UNKNOWN
                        || grantDisposition != GrantDisposition.RETAIN_ACTIVE
                        || !reconciliationRequired) {
                        throw new IllegalArgumentException("invalid unknown reconciliation directive");
                    }
                }
            }
        }
    }

    public record UnknownResolutionDirective(
        String policyId,
        String claimId,
        String attemptId,
        String grantId,
        UnknownResolution resolution,
        GrantDisposition grantDisposition,
        boolean remoteActionConfirmed,
        String evidenceDetail,
        String reason) {
        public UnknownResolutionDirective {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            requireText(claimId, "claimId");
            requireText(attemptId, "attemptId");
            requireText(grantId, "grantId");
            Objects.requireNonNull(resolution, "resolution must not be null");
            if (grantDisposition != GrantDisposition.CONSUME) {
                throw new IllegalArgumentException("resolved UNKNOWN must close the old one-shot authorization");
            }
            if (remoteActionConfirmed != (resolution == UnknownResolution.REMOTE_ACTION_CONFIRMED)) {
                throw new IllegalArgumentException("remoteActionConfirmed does not match resolution");
            }
            requireText(evidenceDetail, "evidenceDetail");
            requireText(reason, "reason");
        }
    }

    private static void requireLiveTerminal(
        TradeCounterActionExecutor.Mode mode,
        TradeCounterActionExecutor.State actualExecutorState,
        TradeCounterActionExecutor.State expectedExecutorState,
        TradeCounterExecutionAttemptRepository.State actualAttemptState,
        TradeCounterExecutionAttemptRepository.State expectedAttemptState,
        GrantDisposition grantDisposition,
        boolean reconciliationRequired) {
        if (mode != TradeCounterActionExecutor.Mode.LIVE
            || actualExecutorState != expectedExecutorState
            || actualAttemptState != expectedAttemptState
            || grantDisposition != GrantDisposition.CONSUME
            || reconciliationRequired) {
            throw new IllegalArgumentException("invalid confirmed live terminal reconciliation directive");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static void requireFingerprint(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }
}
