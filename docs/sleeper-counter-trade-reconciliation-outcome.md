# Sleeper Counter Trade Reconciliation Outcome Evidence

BF-409 governs how BF-407/BF-408 read-only Sleeper transaction reconciliation may qualify as execution-outcome evidence.

Policy: `sleeper-counter-trade-reconciliation-outcome-v1-complete-only-success-no-negative-inference`

This is a pure policy. It does not mutate an execution attempt, consume authorization, or call Sleeper.

## Core rule

Only one exact `MATCH_COMPLETE` Sleeper transaction is eligible as confirmed remote-success evidence.

No other reconciliation state may produce a terminal execution outcome.

## Mapping

| BF-398 reconciliation | BF-409 state | Terminal eligibility |
| --- | --- | --- |
| `MATCH_COMPLETE` | `CONFIRMED_SUCCESS_EVIDENCE` | `CONFIRMED_SUCCESS` |
| `MATCH_PENDING` | `PENDING` | `NONE` |
| `NO_MATCH` | `NO_TERMINAL_OUTCOME` | `NONE` |
| `AMBIGUOUS` | `INCONCLUSIVE` | `NONE` |
| `INCONCLUSIVE` | `INCONCLUSIVE` | `NONE` |
| BF-407 `NOT_AVAILABLE` | `INCONCLUSIVE` | `NONE` |

## No negative inference

`NO_MATCH` is deliberately not failure evidence.

A missing exact transaction in one explicitly requested Sleeper week does not prove that no remote action occurred. The user may have selected the wrong week, the transaction may not yet be visible, or the manual action may never have been performed.

Therefore BF-409 never emits a confirmed-failure eligibility state.

## Pending and ambiguous evidence

`MATCH_PENDING` identifies an exact remote trade, but the platform transaction is not complete. Butler must keep waiting rather than finalize success.

`AMBIGUOUS` means multiple exact transactions matched the frozen coordinates. Butler must not arbitrarily select one as the execution outcome.

## Provenance

The decision retains:

- BF-409 policy ID,
- BF-407 reconciliation service ID,
- BF-398 exact-match policy ID,
- trusted grant ID,
- claim/handoff IDs when evidence was available,
- BF-406 frozen movement SHA-256 when evidence was available,
- explicit Sleeper week,
- matching transaction IDs.

`CONFIRMED_SUCCESS_EVIDENCE` requires exactly one transaction ID.

## Relationship to BF-395/BF-396

BF-395 governs actual executor-result outcomes. A manual Sleeper handoff has no supported live write executor, so BF-409 does not fabricate a BF-395 `LIVE/DISPATCHED` result.

A later local persistence contract may accept BF-409 `CONFIRMED_SUCCESS_EVIDENCE` as retrospective proof that the exact manually submitted trade completed and then finalize the durable execution safely.

That later persistence mutation is outside BF-409.
