# Trade counter execution outcome coordinator

BF-396 adds the durable local coordinator for the governed BF-395 execution outcome contract.

Policy ID:

`trade-counter-execution-outcome-coordinator-v1-atomic-terminal-consume-unknown-lock`

This layer performs **local SQLite mutations only**. It does not send a message, submit a trade, call Sleeper, perform HTTP, or invoke any external platform.

## Inputs

The coordinator accepts only BF-395 governed directives:

- `TradeCounterExecutionOutcomePolicy.Directive`
- `TradeCounterExecutionOutcomePolicy.UnknownResolutionDirective`

It does not accept caller-supplied action, destination, proposal fingerprint, or payload text. Those coordinates remain rooted in the persisted BF-392 attempt and BF-393 claim.

## Dry run

`DRY_RUN_NO_MUTATION` performs no durable mutation:

- no outcome row
- no attempt transition
- no grant consumption
- no reconciliation record

Dry-run evidence is never treated as an execution outcome.

## Confirmed live success

BF-395 `CONFIRMED_SUCCESS` is applied in one database transaction:

1. persist the immutable governed execution outcome,
2. transition the exact `IN_FLIGHT` attempt to `SUCCEEDED`,
3. consume/close the exact one-shot authorization grant,
4. commit all changes together.

If any step fails, the transaction rolls back.

## Definite live no-action failure

BF-395 `CONFIRMED_NO_ACTION_FAILURE` is applied in one transaction:

1. persist the immutable governed outcome,
2. transition `IN_FLIGHT` to `FAILED`,
3. consume/close the old one-shot authorization,
4. commit atomically.

The grant is closed even though the platform action did not occur. The reason is authorization lifecycle safety: the attempt is terminal and Butler must not silently retry it. Any retry requires a fresh explicit authorization.

## Unknown live outcome

BF-395 `UNKNOWN_PENDING_RECONCILIATION` is applied in one transaction:

1. persist the immutable governed UNKNOWN outcome,
2. transition `IN_FLIGHT` to historical terminal state `UNKNOWN`,
3. leave the one-shot authorization active,
4. commit.

The active grant is a retry lock. Butler must not create a new identical authorization or retry the external action while the outcome remains unresolved.

## UNKNOWN reconciliation

A BF-395 UNKNOWN resolution is durably stored in a separate immutable table.

Both resolution states close the old authorization:

- `REMOTE_ACTION_CONFIRMED`
- `REMOTE_NO_ACTION_CONFIRMED`

The historical execution attempt remains `UNKNOWN` in both cases. Reconciliation does not rewrite the original uncertainty.

If remote action is confirmed, the resolution records that the external action occurred. If remote no-action is confirmed, any later retry still requires a new explicit authorization.

## Database enforcement

Once BF-396 is initialized, SQLite enforces the outcome contract beneath Java callers.

### Terminal transition gate

A claimed execution attempt cannot move from `IN_FLIGHT` to:

- `SUCCEEDED`
- `FAILED`
- `UNKNOWN`

unless a matching durable governed outcome already exists in the same database state.

This prevents direct calls to the older BF-392 terminal transition methods from bypassing BF-395.

### Claimed-grant consumption gate

A grant that already has a BF-393 execution claim cannot be consumed unless one of these durable conditions exists:

1. a confirmed success/failure BF-395 outcome whose grant disposition is `CONSUME`, or
2. an immutable UNKNOWN reconciliation whose grant disposition is `CONSUME`.

Therefore:

- an `IN_FLIGHT` claimed grant cannot be prematurely consumed,
- an unresolved `UNKNOWN` grant cannot be consumed to clear the retry lock,
- a direct use of the older grant-consumption API cannot bypass BF-395 once BF-396 is active.

## Idempotence

Only one durable outcome may exist per claim, attempt, and grant.

Re-applying the exact same BF-395 directive returns the existing durable outcome and does not create another mutation.

A different directive for an attempt that already has an outcome fails closed as a mismatch.

Only one durable UNKNOWN resolution may exist per outcome/attempt/grant. Exact repeats are idempotent; conflicting resolutions fail closed.

## Atomicity

For confirmed success/failure, outcome persistence, terminal attempt transition, and grant closure are performed in a single transaction.

For UNKNOWN resolution, resolution persistence and grant closure are performed in a single transaction.

No partial state is intentionally committed.

## Frozen boundary

BF-396 does **not** add:

- a live executor,
- Sleeper credentials,
- Sleeper API calls,
- HTTP/network code,
- CLI execution commands,
- automatic retries,
- message sending,
- trade submission.

The next network-capable platform adapter is a separate explicit boundary and must not be inferred from this coordinator.