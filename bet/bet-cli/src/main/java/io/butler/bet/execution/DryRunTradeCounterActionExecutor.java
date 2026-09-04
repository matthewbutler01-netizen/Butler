package io.butler.bet.execution;

import io.butler.bet.data.TradeCounterExecutionRequestRepository;

import java.util.Objects;

/**
 * Non-executing adapter that proves the trusted request crossing the executor boundary.
 * It performs no network call and mutates no Butler persistence state.
 */
public final class DryRunTradeCounterActionExecutor implements TradeCounterActionExecutor {
    public static final String EXECUTOR_ID = "trade-counter-executor-dry-run-v1-no-side-effect";

    @Override
    public ExecutionResult execute(TradeCounterExecutionRequestRepository.ExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String action = switch (request.action()) {
            case SEND_NEGOTIATION_MESSAGE -> "send negotiation message";
            case SUBMIT_COUNTER_TRADE -> "submit counter trade";
        };
        String detail = "Dry run only: would " + action
            + " to " + request.destination().type() + ":" + request.destination().id()
            + " using exact persisted payload SHA-256 " + request.payloadSha256() + ".";
        return new ExecutionResult(
            EXECUTOR_ID,
            Mode.DRY_RUN,
            State.DRY_RUN_CONFIRMED,
            request.claimId(),
            request.attemptId(),
            request.grantId(),
            request.payloadSha256(),
            detail);
    }
}
