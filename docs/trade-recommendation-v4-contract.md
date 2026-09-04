# Trade Recommendation v4 Compatibility Contract

This document locks the externally meaningful identifiers and behavior of Butler's live flexible material-loss recommendation contract. A future behavioral change must use a new versioned policy identifier rather than silently changing v4 semantics.

## Live policy identifiers

- Recommendation: `trade-recommendation-v4-market-first-flexible-material-loss-veto`
- Strategic veto: `trade-strategic-veto-v3-material-protected-value-plus-flexible-coverage-loss`
- Flexible coverage loss: `trade-flexible-coverage-loss-v1-post-trade-legal-lineup`
- Flexible pressure: `flexible-slot-pressure-v1-combined-relative-quartiles`
- Flexible coverage: `flexible-slot-coverage-v1-direct-reserved-max-value`
- Protected-value materiality: `trade-protected-value-materiality-v1-25-percent-loss`
- Team perspective: `trade-team-perspective-v1-explicit-owner`

Earlier v1-v3 recommendation and v1-v2 strategic-veto identifiers remain valid versioned historical contracts and must not be repurposed for v4 behavior.

## Required evidence

A live v4 directional recommendation requires all of the following to be available:

1. market direction;
2. team posture;
3. future capital;
4. direct positional pressure;
5. combined flexible pressure.

If any required evidence is unavailable, the live package recommendation and team action are `INCONCLUSIVE`. The strategic veto is not evaluated.

A league with no FLEX/SUPERFLEX slots has `NO_FLEXIBLE_REQUIREMENT`, which is an available flexible-pressure state rather than missing evidence.

## Package recommendation vocabulary

The package-level vocabulary remains:

- `SIDE_A_PACKAGE_PREFERRED`
- `SIDE_B_PACKAGE_PREFERRED`
- `HOLD`
- `INCONCLUSIVE`

The explicit team-action vocabulary remains:

- `ACCEPT`
- `REJECT`
- `HOLD`
- `INCONCLUSIVE`

## Market-first and downgrade-only invariant

Market evidence is the only source of package direction.

A strategic veto may convert a directional package recommendation to `HOLD`. It may not:

- create direction from a fair or unavailable market result;
- convert side A preference into side B preference;
- convert side B preference into side A preference;
- infer a team perspective;
- generate `COUNTER`.

## Strategic veto reason vocabulary

The v3 strategic detector reason codes, in deterministic order, are:

1. `LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS`
2. `POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS`
3. `FLEXIBLE_PRESSURE_MATERIAL_POST_TRADE_COVERAGE_LOSS`

When multiple direct positional reasons occur, their internal position order remains `QB`, `RB`, `WR`, `TE`. Flexible coverage loss follows all future-capital and direct-position reasons.

## Materiality invariant

The materiality boundary is fixed at `25%` loss for v4:

- exactly `25%` loss is allowed and classified `WITHIN_TOLERANCE`;
- greater than `25%` loss is `MATERIAL_LOSS`.

Changing this threshold requires a new versioned materiality and recommendation contract.

## Direct protected-value invariants

Low-future-capital protection compares outgoing future-pick value with incoming future-pick value.

Direct `POSITION_PRESSURE` protection compares outgoing player value with incoming player value at the same direct position.

The following substitutions remain prohibited for those direct protection rules:

- player value cannot replenish future-pick protected value;
- another-position player cannot replenish a directly pressured position.

## Flexible protected-coverage invariant

Flexible protection applies only when the selected team is already `FLEXIBLE_PRESSURE`.

The protected measure is not raw outgoing/incoming eligible-player value. It is governed legal flexible lineup coverage.

For the selected team, Butler must:

1. begin from the current governed positional-depth evidence;
2. remove outgoing traded players;
3. add incoming traded players with current governed values;
4. reselect direct QB/RB/WR/TE starters;
5. maximize the remaining legal FLEX/SUPERFLEX coverage using the existing slot-eligibility policy;
6. compare governed pre-trade flexible coverage with recomputed post-trade flexible coverage.

Ordinary FLEX permits `RB`, `WR`, and `TE`. SUPERFLEX permits `QB`, `RB`, `WR`, and `TE`.

Legal cross-position substitution is therefore allowed inside flexible coverage. It does not alter direct-position veto semantics.

Before post-trade loss is used, the recomputed pre-trade flexible coverage must match the coverage evidence that produced the governed flexible-pressure tier. A mismatch is an error, not a reason to approximate.

## Flexible loss assessment vocabulary

The flexible loss analyzer states are fixed as:

- `NOT_PROTECTED`
- `INSUFFICIENT_EVIDENCE`
- `WITHIN_TOLERANCE`
- `MATERIAL_LOSS`

`FLEXIBLE_BALANCED`, `FLEXIBLE_STRENGTH`, and `NO_FLEXIBLE_REQUIREMENT` are not protected flexible areas and cannot independently create the flexible material-loss reason.

## Compatibility rule

Any future change to required evidence, action vocabulary, reason codes, reason ordering, materiality threshold, flexible eligibility, post-trade lineup recomputation, direct protected-value categories, market-direction ownership, or downgrade-only semantics must be introduced as a deliberate new versioned contract.
