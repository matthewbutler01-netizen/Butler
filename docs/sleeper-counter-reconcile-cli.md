# Sleeper Counter Reconcile CLI

BF-408 exposes the BF-407 read-only reconciliation service through one trusted-grant command:

`butler trade counter-reconcile <trusted-grant-id> <sleeper-week>`

BF-411 additionally displays the BF-409 reconciliation-outcome eligibility decision while preserving the same read-only command boundary.

## Input boundary

The command accepts only:

- one trusted persisted authorization grant ID,
- one explicit Sleeper week from 1 through 30.

It does not accept or override league IDs, roster IDs, players, draft picks, action, destination, transaction IDs, or the not-before timestamp.

## Evidence path

The command loads the durable execution/handoff chain and BF-406 provider snapshot through BF-407. BF-407 then reads the documented Sleeper transactions endpoint for the explicitly supplied week and delegates matching to BF-398.

The BF-402 first presentation timestamp remains the not-before boundary, preventing an older identical trade from satisfying the current handoff.

After BF-398 reconciliation, BF-409 classifies the evidence without mutating any local or remote state.

## Output

When evidence is available the command prints:

- trusted grant ID,
- explicit Sleeper week,
- execution claim and handoff IDs,
- frozen provider-movement SHA-256,
- not-before boundary,
- observed transaction count,
- BF-398 reconciliation policy/state/reason,
- matching transaction IDs,
- evidence-incomplete status,
- BF-409 outcome-eligibility policy/state/reason code,
- terminal-outcome eligibility,
- BF-409 transaction IDs and reason.

When trusted state is unavailable, the command states that no Sleeper transaction evidence was evaluated and prints BF-409 `INCONCLUSIVE` / `TRUSTED_RECONCILIATION_UNAVAILABLE` with no terminal eligibility.

## BF-409 eligibility semantics

The command exposes, but does not apply, these governed mappings:

- exactly one `MATCH_COMPLETE` -> `CONFIRMED_SUCCESS_EVIDENCE` / `CONFIRMED_SUCCESS`,
- `MATCH_PENDING` -> `PENDING` / no terminal eligibility,
- `NO_MATCH` -> `NO_TERMINAL_OUTCOME` / no terminal eligibility,
- `AMBIGUOUS` -> `INCONCLUSIVE` / no terminal eligibility,
- `INCONCLUSIVE` -> `INCONCLUSIVE` / no terminal eligibility,
- unavailable trusted reconciliation -> `INCONCLUSIVE` / no terminal eligibility.

`NO_MATCH` is explicitly not failure evidence.

## Safety boundary

This command remains read-only.

Even when BF-409 reports `CONFIRMED_SUCCESS_EVIDENCE`, BF-411 does not:

- mark the execution `SUCCEEDED`, `FAILED`, or `UNKNOWN`,
- invoke the BF-410 finalizer,
- mutate a Sleeper trade,
- consume the one-shot authorization grant,
- retry an action,
- infer a different Sleeper week.

BF-410 is a separate local-only coordinator that can later atomically finalize an exact confirmed-success readback. BF-411 only shows whether the current reconciliation evidence is eligible for that separate governed step.
