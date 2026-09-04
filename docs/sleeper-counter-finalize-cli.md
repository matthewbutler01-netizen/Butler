# Sleeper Counter Finalize CLI

BF-412 exposes the BF-410 local-only manual-trade finalizer through an explicit command:

`butler trade counter-finalize <trusted-grant-id> <sleeper-week>`

## Purpose

This command closes Butler's local execution lifecycle after the user has manually completed a governed counter trade in Sleeper and Sleeper's official transaction readback proves the exact trade completed.

It does **not** submit, accept, reject, modify, or retry a Sleeper trade.

## Input boundary

The command accepts only:

- one trusted persisted authorization grant ID,
- one explicit Sleeper week from 1 through 30.

It does not accept league IDs, roster IDs, players, draft picks, transaction IDs, action, destination, movement, or a not-before timestamp as caller overrides.

## Governed path

For each invocation Butler performs this sequence:

1. BF-407 loads the durable execution attempt, claim, manual handoff, and BF-406 immutable provider-movement snapshot.
2. BF-407 reads Sleeper's documented transactions **GET** endpoint for the explicit week.
3. BF-398 performs exact transaction matching after the BF-402 first-presentation boundary.
4. BF-409 maps the reconciliation evidence to terminal-outcome eligibility.
5. BF-410 receives that exact BF-409 decision.
6. Only `CONFIRMED_SUCCESS_EVIDENCE` / `CONFIRMED_SUCCESS` can be atomically persisted as local success.

When BF-410 applies success, it atomically:

- records the retrospective Sleeper terminal-outcome provenance,
- marks the matching Butler execution attempt `SUCCEEDED`,
- consumes the matching one-shot authorization grant.

## Non-eligible states

These BF-409 outcomes do not finalize local execution state:

- `PENDING`,
- `NO_TERMINAL_OUTCOME`,
- `INCONCLUSIVE`.

Therefore `MATCH_PENDING`, `NO_MATCH`, `AMBIGUOUS`, `INCONCLUSIVE`, and unavailable trusted reconciliation do not mark the attempt `SUCCEEDED`, `FAILED`, or `UNKNOWN` and do not consume the authorization through BF-410.

`NO_MATCH` remains explicitly non-negative evidence. Absence in the requested week is not proof that the manual trade failed or never occurred.

## Idempotence

BF-410 preserves one immutable terminal outcome per trusted manual-trade claim.

An exact repeat of the same completed-trade evidence returns `ALREADY_APPLIED` and preserves the original terminal audit record. A different terminal outcome cannot overwrite or rebind it.

BF-407 reconstructs readback coordinates from durable attempt/claim/handoff/snapshot state, so exact repeat finalization can still be recognized after the attempt is already `SUCCEEDED` and the grant is consumed.

## Output

The command prints:

- trusted grant ID,
- explicit Sleeper week,
- BF-407 reconciliation service state/reason,
- BF-398 reconciliation state and matching transaction IDs when available,
- BF-409 eligibility policy/state/reason code,
- terminal-outcome eligibility,
- BF-410 finalization state/reason,
- stored attempt ID, transaction ID, terminal state, grant disposition, and applied timestamp when an outcome exists.

## Safety boundary

Sleeper access remains read-only and GET-only.

BF-412 can mutate **local Butler state** only after exact completed readback. It has no Sleeper write transport and cannot:

- submit a trade,
- accept or reject a trade,
- send a negotiation message,
- change a Sleeper roster,
- infer a different week,
- infer failure from missing transaction evidence.

The existing `trade counter-reconcile` command remains read-only and never invokes BF-410. `trade counter-finalize` is the separate explicit command that can apply the already-governed local finalization step.
