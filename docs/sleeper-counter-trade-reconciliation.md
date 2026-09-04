# Sleeper counter trade reconciliation contract

Policy ID: `sleeper-trade-reconciliation-v1-exact-assets-rosters-created-after`

## Purpose

BF-398 converts read-only Sleeper transaction rows into governed evidence about whether a manually submitted Butler counter trade appears in Sleeper.

It does not submit a trade and does not mutate BF-395/BF-396 execution state.

## Inputs

The policy requires a fully resolved Sleeper expectation:

- numeric Sleeper league ID;
- Sleeper transaction round/week;
- exactly two Sleeper roster IDs;
- exact player `adds` destinations;
- exact player `drops` sources;
- exact draft-pick movements;
- optional expected Sleeper creator user ID;
- `notBeforeEpochMillis` tied to the governed handoff/execution attempt.

The not-before boundary is mandatory in the model because an older identical trade must never be mistaken for the current counter.

## Matching

A Sleeper row is structurally eligible only when:

1. `type == trade`;
2. involved roster IDs exactly match the expected two-roster set;
3. player adds exactly match;
4. player drops exactly match;
5. draft-pick movements exactly match;
6. creator matches when an expected creator is available;
7. `created >= notBeforeEpochMillis`.

No partial or score-based matching is allowed.

## States

### `MATCH_PENDING`

Exactly one eligible trade has Sleeper status `pending`.

This proves that matching trade evidence exists, but does not prove completion/acceptance.

### `MATCH_COMPLETE`

Exactly one eligible trade has Sleeper status `complete`.

This is strong read-only evidence that the matching trade completed on Sleeper.

### `NO_MATCH`

No eligible trade after the not-before boundary exactly matches the expected coordinates.

`NO_MATCH` is not, by itself, proof that no submission occurred. Sleeper propagation delay, week selection, or other evidence gaps must be handled by later orchestration.

### `AMBIGUOUS`

More than one pending/complete trade exactly matches the governed coordinates.

Butler must not choose a transaction ID arbitrarily.

### `INCONCLUSIVE`

Exact structural evidence exists but cannot satisfy the governed evidence contract, currently because:

- the exact transaction has a status outside `pending|complete`; or
- the exact transaction lacks a creation timestamp needed to enforce the not-before boundary.

## Non-goals

BF-398 does not:

- resolve Butler league/team/player/pick IDs into Sleeper IDs;
- choose the current Sleeper round/week;
- decide how long to wait before treating `NO_MATCH` as meaningful;
- turn `MATCH_COMPLETE` directly into BF-395 UNKNOWN reconciliation;
- reconcile negotiation-message delivery;
- call any Sleeper write endpoint.

Those remain separate governed layers.
