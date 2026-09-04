# Sleeper Counter Trade Outcome Coordinator

BF-410 durably finalizes one manually submitted Sleeper counter trade only when BF-409 has classified exact completed readback as confirmed-success evidence.

Coordinator: `sleeper-counter-trade-outcome-coordinator-v1-exact-complete-atomic-success-consume`

## Eligibility

The coordinator accepts BF-409 decisions.

Only all of the following may mutate storage:

- state `CONFIRMED_SUCCESS_EVIDENCE`,
- terminal eligibility `CONFIRMED_SUCCESS`,
- exactly one exact Sleeper transaction ID,
- trusted claim/handoff/grant coordinates matching persisted Butler state,
- BF-406 frozen provider movement SHA-256 matching persisted snapshot,
- execution attempt still `IN_FLIGHT`,
- authorization grant still active.

Every other BF-409 state returns `NOT_ELIGIBLE` with no mutation.

In particular:

- `MATCH_PENDING` does not finalize,
- `NO_MATCH` does not become failure,
- ambiguous/inconclusive evidence does not finalize.

## Atomic mutation

For eligible exact-complete evidence, one SQLite transaction:

1. persists an immutable Sleeper-specific terminal outcome row,
2. marks the matching execution attempt `SUCCEEDED`,
3. records the exact Sleeper transaction ID and week in the durable audit outcome,
4. consumes the one-shot authorization grant.

All changes commit together or roll back together.

Repeating the exact same evidence is idempotent and returns the original stored outcome.

A different terminal outcome cannot replace it.

## Provenance

BF-410 does not fabricate BF-395 `LIVE/DISPATCHED` executor evidence.

Its durable outcome stores separate provenance for:

- BF-410 coordinator,
- BF-409 reconciliation-outcome evidence policy,
- BF-407 reconciliation service,
- BF-398 exact-match policy,
- trusted claim/handoff/attempt/grant IDs,
- BF-406 frozen movement SHA-256,
- explicit Sleeper week,
- exact completed Sleeper transaction ID.

## Database guards

BF-396 originally required a durable BF-395 executor outcome before an `IN_FLIGHT` attempt could become terminal or a claimed grant could be consumed.

BF-410 upgrades those database-level guards so either of these governed paths is valid:

1. the existing BF-395/BF-396 executor outcome path, or
2. a BF-410 exact-complete manual Sleeper trade outcome.

The original executor path remains supported unchanged.

Direct SQL attempts to mark the manual trade `SUCCEEDED` or consume its authorization without a durable governed outcome remain rejected.

## Safety boundary

BF-410 performs no Sleeper request and no external write.

It only finalizes local Butler state after exact completed transaction evidence has already been obtained through the supported read-only Sleeper API.
