# Sleeper Counter Trade Snapshot Reconciliation

BF-407 reconciles one trusted manual counter-trade handoff against Sleeper's documented read-only transaction endpoint using the immutable BF-406 provider/movement snapshot.

Service: `sleeper-counter-trade-snapshot-reconciliation-v1-explicit-week-read-only`

## Inputs

The persisted service path accepts:

- one trusted authorization grant ID,
- one explicit Sleeper week/round from 1 through 30.

The caller does not supply league IDs, roster IDs, players, picks, action, destination, or reconciliation timestamps.

Butler loads the durable execution attempt, READY claim, manual handoff presentation, and BF-406 provider snapshot from trusted local state.

## Reconciliation coordinates

BF-407 constructs the BF-398 expected trade from:

- Sleeper league ID frozen by BF-406,
- Sleeper roster IDs frozen by BF-406,
- exact player adds/drops frozen by BF-406,
- exact draft-pick movements frozen by BF-406,
- explicit caller-supplied Sleeper week,
- BF-402 first `presented_at` as the exact not-before timestamp,
- no inferred creator user ID.

It then calls only:

`GET /v1/league/<sleeper-league-id>/transactions/<week>`

through the existing GET-only Sleeper client and delegates matching entirely to BF-398.

## Result semantics

A successful read returns the unchanged BF-398 states:

- `MATCH_PENDING`
- `MATCH_COMPLETE`
- `NO_MATCH`
- `AMBIGUOUS`
- `INCONCLUSIVE`

`NO_MATCH` means only that no exact trade matching the frozen coordinates was observed in the explicitly requested Sleeper week after the first-presentation boundary.

It is not evidence that the user never attempted the trade in another week.

## Fail closed

The service returns `NOT_AVAILABLE` before any Sleeper request if trusted local state is missing, including:

- execution attempt,
- READY claim,
- durable handoff presentation,
- BF-406 expectation snapshot.

Snapshot/handoff coordinate disagreement throws rather than being reconciled heuristically.

## Safety boundary

BF-407 is read-only.

It does not:

- send or submit a trade,
- use private Sleeper endpoints,
- infer the Sleeper week,
- mutate the execution attempt,
- mark `SUCCEEDED`, `FAILED`, or `UNKNOWN`,
- consume authorization,
- automatically interpret reconciliation evidence as an execution outcome.

Turning a read reconciliation result into a BF-395/BF-396 terminal execution outcome remains a separate governed step.
