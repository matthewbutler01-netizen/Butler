package io.butler.bet.execution;

import io.butler.bet.data.TradeCounterExecutionRequestRepository;

/** Platform-neutral executor boundary for one trusted persisted counter execution request. */
public interface TradeCounterActionExecutor {
    ExecutionResult execute(TradeCounterExecutionRequestRepository.ExecutionRequest request);

    record ExecutionResult(
        String executorId,
        Mode mode,
        State state,
        String claimId,
        String attemptId,
        String grantId,
        String payloadSha256,
        String detail) {
        public ExecutionResult {
            if (executorId == null || executorId.isBlank()) {
                throw new IllegalArgumentException("executorId must not be blank");
            }
            if (mode == null) throw new IllegalArgumentException("mode must not be null");
            if (state == null) throw new IllegalArgumentException("state must not be null");
            if (claimId == null || claimId.isBlank()) throw new IllegalArgumentException("claimId must not be blank");
            if (attemptId == null || attemptId.isBlank()) throw new IllegalArgumentException("attemptId must not be blank");
            if (grantId == null || grantId.isBlank()) throw new IllegalArgumentException("grantId must not be blank");
            if (payloadSha256 == null || !payloadSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("payloadSha256 must be lowercase SHA-256");
            }
            if (detail == null || detail.isBlank()) {
                throw new IllegalArgumentException("detail must not be blank");
            }
        }
    }

    enum Mode {
        DRY_RUN,
        LIVE
    }

    enum State {
        DRY_RUN_CONFIRMED,
        DISPATCHED,
        DEFINITE_FAILURE,
        UNKNOWN
    }
}
