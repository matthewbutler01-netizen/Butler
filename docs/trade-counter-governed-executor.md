# Governed Trade Counter Executor

BF-394 defines the execution boundary that a future fantasy-platform adapter must implement without allowing caller-supplied trade intent to bypass Butler's trusted authorization, journal, and claim chain.

Execution-request policy: `trade-counter-execution-request-v1-persisted-claim-attempt-only`

Dry-run executor: `trade-counter-executor-dry-run-v1-no-side-effect`

BF-394 performs **no external call** and changes no authorization or execution state.

## Trusted execution request

`TradeCounterExecutionRequestRepository` accepts only a persisted BF-393 `claim_id`.

It reconstructs the execution request by joining Butler's trusted persisted state:

- BF-393 execution claim;
- BF-392 execution attempt; and
- trusted one-shot authorization grant.

The caller does not provide action, destination, proposal fingerprint, payload kind, payload text, or payload hash. Those values come only from Butler storage.

A request is available only when the BF-393 claim exists and uses the governed claim policy, its BF-392 attempt is still `IN_FLIGHT`, the trusted authorization grant remains unconsumed, and the persisted action/destination/payload are internally compatible. A missing claim returns no request; a consumed grant or non-`IN_FLIGHT` attempt fails closed.

## Exact payload verification

The execution request retains the BF-392 exact payload text and SHA-256 identity. Butler recomputes SHA-256 over the exact UTF-8 payload bytes and requires it to match the stored hash. No trimming or normalization is applied.

## Platform-neutral executor interface

`TradeCounterActionExecutor` exposes `execute(ExecutionRequest) -> ExecutionResult`.

The interface defines modes `DRY_RUN` and `LIVE`, and result states `DRY_RUN_CONFIRMED`, `DISPATCHED`, `DEFINITE_FAILURE`, and `UNKNOWN`. `LIVE` and live-result states are future governed vocabulary only; BF-394 provides no live executor.

## Dry-run executor

`DryRunTradeCounterActionExecutor` is the only BF-394 implementation. It reports whether the future action would send a negotiation message or submit a counter trade, the trusted destination, and the exact payload SHA-256, then returns `DRY_RUN_CONFIRMED`.

It performs no network call, does not access Sleeper, changes no BF-392/BF-393 state, and does not consume the authorization grant. A dry run therefore leaves the attempt `IN_FLIGHT` and the grant unconsumed.

## Action-specific request shape

- `SEND_NEGOTIATION_MESSAGE` requires destination `MANAGER` and payload `NEGOTIATION_MESSAGE_TEXT`.
- `SUBMIT_COUNTER_TRADE` requires destination `LEAGUE` and payload `COUNTER_TRADE_REQUEST_JSON`.

BF-394 intentionally does not define a live Sleeper trade-request schema. A real adapter must wait for a governed serialization and outcome/reconciliation contract.

## No CLI surface yet

BF-394 adds no CLI command or router target. This avoids creating an executable-looking surface before outcome reconciliation is governed. Any later diagnostic CLI must not accept action, destination, or payload arguments that can override persisted trusted state.

## Safety boundary

BF-394 does not call Sleeper or another fantasy platform, send a message, submit a trade, consume a grant, transition an attempt to `SUCCEEDED`/`FAILED`/`UNKNOWN`, retry execution, change Trade Recommendation v5, change counter semantics, or change the exact `AUTHORIZE_ONCE` contract.

The next recommended layer is BF-395: govern how future live executor outcomes reconcile into the BF-392 journal and one-shot authorization grant, still using fake/deterministic outcomes and no real Sleeper adapter.
