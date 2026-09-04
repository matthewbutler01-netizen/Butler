# Trade Recommendation

Butler's trade recommendation surface is conservative and evidence-first. It converts governed trade evidence into a package recommendation and, only when an explicit team perspective is supplied, into a team action.

## Command

```text
butler trade recommendation <league-id> <season> <side-a-assets> <side-b-assets> <side-a|side-b> [source] [--minimum-as-of YYYY-MM-DD]
```

Assets are comma-separated. Bare IDs are players. Explicit forms are `player:<id>` and `pick:<draft-pick-id>`.

The perspective identifies the team giving that side's package and receiving the opposite package:

- `side-a`: evaluate the trade for the team giving side A and receiving side B.
- `side-b`: evaluate the trade for the team giving side B and receiving side A.

## Governed policies

The recommendation output identifies both governing policies:

- package recommendation: `trade-recommendation-v1-conservative-evidence-first`
- team perspective: `trade-team-perspective-v1-explicit-owner`

No hidden weighting or strategic override is applied.

## Evidence gates

A directional recommendation requires all governed evidence gates to be available:

- market direction
- team posture
- future draft capital
- positional pressure

The CLI reports each gate independently. If one or more required gates are unavailable, the action is `INCONCLUSIVE` and the unavailable gates are named.

Market direction is unavailable when the governed market-edge policy cannot classify the trade from current comparable evidence. Posture, future-capital, and positional-pressure availability are inherited from their upstream governed analyzers and their freshness/completeness guards.

## Package recommendation

The conservative package-level policy emits one of:

- `SIDE_A_PACKAGE_PREFERRED`
- `SIDE_B_PACKAGE_PREFERRED`
- `HOLD`
- `INCONCLUSIVE`

Directional package preference comes only from the governed market-edge classification after all supporting evidence gates are complete.

`HOLD` means the governed market comparison is inside the fairness band.

`INCONCLUSIVE` means required governed evidence is incomplete or market direction is unavailable.

## Team action

The explicit perspective policy translates the package recommendation into one of:

- `ACCEPT`
- `REJECT`
- `HOLD`
- `INCONCLUSIVE`

For the side-A team, a side-B package preference maps to `ACCEPT`; a side-A package preference maps to `REJECT`. Side B uses the exact mirrored mapping.

`HOLD` and `INCONCLUSIVE` are preserved unchanged for either perspective.

## Scope boundary

The current policy does not:

- blend posture, future capital, positional pressure, and market edge with numeric weights;
- override market direction because of roster need or strategic preference;
- generate a `COUNTER` recommendation;
- select a winner when governed evidence is incomplete;
- infer a team perspective when the caller did not provide one.

Any future change that introduces weighting, strategic overrides, counter-offer behavior, confidence-based relaxation, or new action semantics is a recommendation-policy change and should be versioned and reviewed as such.
