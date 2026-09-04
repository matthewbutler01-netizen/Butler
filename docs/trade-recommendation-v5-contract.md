# Trade Recommendation v5 Compatibility Contract

This document locks the externally meaningful identifiers and behavior of Butler's live flexible transition material-loss recommendation contract. Future behavioral changes must use new versioned policy identifiers rather than silently changing v5 semantics.

## Live policy identifiers

- Recommendation: `trade-recommendation-v5-market-first-flexible-transition-material-loss-veto`
- Strategic veto: `trade-strategic-veto-v4-material-protected-value-plus-flexible-transition-loss`
- Flexible pressure transition: `trade-flexible-pressure-transition-v1-post-trade-league-relative`
- Flexible post-trade depth: `trade-flexible-post-trade-depth-v1-two-team-exchange`
- Flexible coverage loss: `trade-flexible-coverage-loss-v1-post-trade-legal-lineup`
- Flexible pressure: `flexible-slot-pressure-v1-combined-relative-quartiles`
- Flexible coverage: `flexible-slot-coverage-v1-direct-reserved-max-value`
- Protected-value materiality: `trade-protected-value-materiality-v1-25-percent-loss`
- Team perspective: `trade-team-perspective-v1-explicit-owner`

Earlier v1-v4 recommendation and v1-v3 strategic-veto identifiers remain valid historical contracts. In particular, v4 remains a frozen compatibility surface and must not acquire v5 transition semantics.

## Required evidence

A live v5 directional recommendation requires all of the following to be available:

1. market direction;
2. team posture;
3. future capital;
4. direct positional pressure;
5. combined flexible pressure.

If any required evidence is unavailable, the live package recommendation and team action are `INCONCLUSIVE`, and the strategic veto is not evaluated.

A league with no FLEX/SUPERFLEX slots has `NO_FLEXIBLE_REQUIREMENT`, which is an available flexible-pressure state rather than missing evidence.

## Package and action vocabulary

Package recommendations remain:

- `SIDE_A_PACKAGE_PREFERRED`
- `SIDE_B_PACKAGE_PREFERRED`
- `HOLD`
- `INCONCLUSIVE`

Team actions remain:

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

## Strategic veto reason vocabulary and order

The v4 strategic detector reason codes, in deterministic order, are:

1. `LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS`
2. `POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS`
3. `FLEXIBLE_PRESSURE_MATERIAL_POST_TRADE_COVERAGE_LOSS`
4. `FLEXIBLE_MATERIAL_LOSS_TRANSITION_TO_PRESSURE`

When multiple direct positional reasons occur, their internal position order remains `QB`, `RB`, `WR`, `TE`. Existing flexible-pressure protection follows all direct reasons. Transition-to-pressure protection is last.

A valid assessment cannot contain both flexible reason codes for the same selected team: an already-pressured team is handled by the existing-pressure rule, while a non-pressure team may be handled by the transition rule.

## Materiality invariant

The materiality boundary remains fixed at `25%` loss:

- exactly `25%` loss is allowed and does not trigger a material-loss veto;
- greater than `25%` loss is material.

Changing this threshold requires a new versioned materiality and recommendation contract.

## Existing weakness protection

v5 preserves all v4 existing-weakness protection:

- low future capital compares outgoing future-pick value with incoming future-pick value;
- direct `POSITION_PRESSURE` compares outgoing player value with incoming player value at the same direct position;
- existing `FLEXIBLE_PRESSURE` recomputes legal post-trade FLEX/SUPERFLEX coverage and vetoes only when coverage loss exceeds `25%`.

Player value cannot replenish future-pick protected value, and another-position player cannot replenish a directly pressured position. Legal cross-position substitution remains allowed inside FLEX/SUPERFLEX coverage because the lineup optimizer determines legal slot assignments.

## Transition-to-pressure protection

v5 adds one new protected condition for teams that are not already `FLEXIBLE_PRESSURE`.

Butler must:

1. start from the full governed league positional-depth evidence;
2. apply the proposed exchange to both trade teams using the same roster-mutation algorithm;
3. recompute legal FLEX/SUPERFLEX coverage for the full league;
4. rerun the governed league-relative flexible-pressure classification;
5. compare the selected team's pre-trade tier with its post-trade tier;
6. measure the selected team's legal flexible coverage loss using the existing 25% materiality policy.

The transition reason is eligible only when both conditions are true:

- the selected team moves from a non-pressure flexible tier into `FLEXIBLE_PRESSURE`; and
- legal flexible coverage loss is greater than `25%`.

Therefore:

- `FLEXIBLE_BALANCED -> FLEXIBLE_PRESSURE` with more than 25% loss can veto;
- `FLEXIBLE_STRENGTH -> FLEXIBLE_PRESSURE` with more than 25% loss can veto;
- exactly 25% loss does not veto even if the team moves into pressure;
- a material coverage loss that does not move the team into `FLEXIBLE_PRESSURE` does not trigger the transition reason;
- a team already in `FLEXIBLE_PRESSURE` remains governed by the existing-pressure flexible-loss rule rather than the transition rule.

The full-league reranking must reflect both sides of the trade. Updating only the selected team's roster is not sufficient because the opposite trade team's changed flexible coverage can move the league-relative quartile boundary.

## Perspective invariant

Transition evidence is evaluated for the explicitly selected team perspective. The same trade may therefore produce a strategic `HOLD` for one side and remain directional for the other side.

The market direction remains shared. Perspective changes which team's strategic protections are evaluated; it does not change market evidence.

## Compatibility rule

Any future change to required evidence, action vocabulary, reason codes, reason ordering, materiality threshold, flexible eligibility, league-relative reranking, post-trade roster mutation, protected-value categories, market-direction ownership, perspective semantics, or downgrade-only behavior must be introduced as a deliberate new versioned contract.
