# Sleeper Counter Reconcile CLI

BF-408 exposes the BF-407 read-only reconciliation service through one trusted-grant command:

`butler trade counter-reconcile <trusted-grant-id> <sleeper-week>`

## Input boundary

The command accepts only:

- one trusted persisted authorization grant ID,
- one explicit Sleeper week from 1 through 30.

It does not accept or override league IDs, roster IDs, players, draft picks, action, destination, transaction IDs, or the not-before timestamp.

## Evidence path

The command loads the durable execution/handoff chain and BF-406 provider snapshot through BF-407. BF-407 then reads the documented Sleeper transactions endpoint for the explicitly supplied week and delegates matching to BF-398.

The BF-402 first presentation timestamp remains the not-before boundary, preventing an older identical trade from satisfying the current handoff.

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
- evidence-incomplete status.

When trusted state is unavailable, the command states that no Sleeper transaction evidence was evaluated.

## Safety boundary

This command is read-only.

Even `MATCH_COMPLETE` is evidence only. BF-408 does not:

- declare execution success,
- mark the execution `SUCCEEDED`, `FAILED`, or `UNKNOWN`,
- mutate a Sleeper trade,
- consume the one-shot authorization grant,
- retry an action,
- infer a different Sleeper week.

A later policy may decide how exact reconciliation evidence maps into BF-395/BF-396 execution-outcome directives. That mapping is intentionally outside BF-408.
