# Trade Counter Execution Outcome and Reconciliation

BF-395 governs how a future live executor result should affect Butler's BF-392 execution journal and one-shot authorization. The BF-395 implementation is a **pure policy only**: it does not mutate SQLite and does not call a fantasy platform.

Policy: `trade-counter-execution-outcome-v1-live-terminal-no-retry-unknown-reconcile`

## Trusted binding

The policy receives:

- the BF-394 execution request reconstructed from persisted BF-392/BF-393 state; and
- an executor result.

The executor result must exactly match the trusted request's:

- claim ID;
- attempt ID;
- grant ID; and
- exact payload SHA-256.

A mismatch fails closed before any reconciliation directive is produced.

## Dry-run result

`DRY_RUN + DRY_RUN_CONFIRMED` produces `DRY_RUN_NO_MUTATION`.

The directive requires:

- no BF-392 terminal transition;
- authorization grant remains active; and
- no reconciliation process.

Dry-run output can never be treated as evidence that a real external side effect occurred.

## Confirmed live success

`LIVE + DISPATCHED` maps to:

- outcome `CONFIRMED_SUCCESS`;
- BF-392 terminal state `SUCCEEDED`; and
- authorization disposition `CONSUME`.

For BF-395, `DISPATCHED` has a deliberately strict meaning: **the future live adapter has affirmative evidence that the platform accepted/created the requested side effect**. Merely writing bytes to a socket, issuing an HTTP request, or receiving an ambiguous response is not enough. Those cases must be `UNKNOWN` unless the platform result is verified.

## Definite no-action failure

`LIVE + DEFINITE_FAILURE` maps to:

- outcome `CONFIRMED_NO_ACTION_FAILURE`;
- BF-392 terminal state `FAILED`; and
- authorization disposition `CONSUME`.

Consuming the authorization here does not claim the remote action succeeded. It closes the old one-shot execution attempt.

This is required because:

1. BF-392 terminal attempts cannot be retried; and
2. an unconsumed active authorization blocks a duplicate authorization for the same fingerprint/action/destination.

Therefore, after a definite failure, any retry must begin with a **fresh explicit `AUTHORIZE_ONCE` grant**. Butler must never silently reuse the failed attempt or authorization.

## Unknown outcome

`LIVE + UNKNOWN` maps to:

- outcome `UNKNOWN_PENDING_RECONCILIATION`;
- BF-392 terminal state `UNKNOWN`;
- authorization disposition `RETAIN_ACTIVE`; and
- `reconciliationRequired = true`.

The active grant intentionally acts as a retry lock. Butler must not issue another identical authorization or execution while it is unknown whether the first external request actually succeeded.

An `UNKNOWN` outcome includes timeouts, lost acknowledgements, interrupted responses, or any other case where Butler cannot prove whether the remote side effect occurred.

## Unknown reconciliation

BF-395 defines two eventual resolution classes:

### `REMOTE_ACTION_CONFIRMED`

Trusted reconciliation evidence proves the external side effect exists.

Directive:

- consume/close the one-shot authorization;
- record that remote action was confirmed; and
- leave the historical BF-392 attempt state as `UNKNOWN` for audit integrity.

The attempt remains `UNKNOWN` because that state records what Butler knew at the end of the original execution call. Reconciliation is separate historical evidence rather than a rewrite of the original event.

### `REMOTE_NO_ACTION_CONFIRMED`

Trusted reconciliation evidence proves the external side effect did not occur.

Directive:

- consume/close the old one-shot authorization;
- record that no remote action was found; and
- require a fresh explicit authorization for any retry.

Again, the historical attempt remains `UNKNOWN`.

## No automatic retry

No BF-395 directive authorizes retrying the same attempt.

- `SUCCEEDED` is terminal.
- `FAILED` is terminal and the old grant is closed.
- `UNKNOWN` is terminal and blocks new execution until reconciled.
- reconciled `UNKNOWN` closes the old grant; retry after confirmed no-action still requires a new explicit authorization.

This makes every additional real external attempt separately visible and separately authorized.

## Future durable application

A later persistence coordinator may apply these directives atomically to:

- the BF-392 execution-attempt journal;
- the authorization grant; and
- a durable reconciliation audit table.

That coordinator must validate current stored claim/request/attempt/grant state again before mutation. It must not trust a stale policy directive by itself.

BF-395 intentionally does not implement that mutation coordinator yet.

## Safety boundary

BF-395 does not:

- provide a live executor;
- call Sleeper or another fantasy platform;
- send a message;
- submit a trade;
- mutate the BF-392 attempt journal;
- consume an authorization grant;
- persist reconciliation evidence;
- retry an execution;
- change Trade Recommendation v5; or
- change counter recommendation/proposal/authorization semantics.

After BF-395, Butler has the governance contracts needed to design a durable outcome-application coordinator. The first real network-capable Sleeper adapter remains a separate external-action boundary and requires explicit approval before implementation/use.
