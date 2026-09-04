# Trade Counter Opportunity Gate

BF-374 adds the first perspective-aware decision layer above Butler's governed counter evidence.
It answers one narrow question: **does this team have a governed counter opportunity?**

Policy: `trade-counter-opportunity-v1-v5-reject-plus-strategic-eligibility`

CLI:

`butler trade counter-decision <league-id> <season> <side-a-assets> <side-b-assets> <side-a|side-b> [source] [--minimum-as-of YYYY-MM-DD]`

The perspective has the same meaning as live Trade Recommendation v5: Side A owns/gives the Side A package and receives Side B; Side B owns/gives Side B and receives Side A.

## Inputs

The opportunity gate consumes:

1. the live Trade Recommendation v5 package recommendation;
2. the live v5 team-perspective action;
3. the explicit team perspective;
4. whether all v5 required evidence is complete; and
5. the BF-373 strategically eligible candidate set when candidate evidence is required.

The policy validates that the supplied team action matches the package recommendation under `trade-team-perspective-v1-explicit-owner`.

## Decision states

The counter-opportunity vocabulary is deliberately separate from the live recommendation action vocabulary:

- `COUNTER_AVAILABLE`
- `NO_COUNTER`
- `INCONCLUSIVE`

`COUNTER_AVAILABLE` is an opportunity state only. It is not a `COUNTER` action.

## Gate order

The gate is deterministic:

1. If required v5 evidence is incomplete, the counter opportunity is `INCONCLUSIVE`.
2. If complete v5 returns `ACCEPT` or `HOLD`, the result is `NO_COUNTER`. Strategic candidate analysis is not required.
3. Only complete v5 `REJECT` enters the strategic-eligibility gate.
4. If BF-373 strategic eligibility is unavailable for a v5 `REJECT`, the result is `INCONCLUSIVE`.
5. If v5 is `REJECT` but no strategically eligible candidate exists, the result is `NO_COUNTER`.
6. If v5 is `REJECT` and one or more strategically eligible candidates exist, the result is `COUNTER_AVAILABLE`.

Because Trade Recommendation v5 is market-first and strategic vetoes are downgrade-only, a complete v5 `REJECT` represents a directional market disadvantage for the selected team. A strategic veto would downgrade that direction to `HOLD`, not preserve `REJECT`.

## BF-375 unique-best candidate selection

BF-375 adds a separately versioned selector above `COUNTER_AVAILABLE`:

Policy: `trade-counter-candidate-selection-v1-unique-best-market-criteria-fail-ambiguous`

Selection states:

- `SELECTED`
- `AMBIGUOUS`
- `NO_SELECTION`
- `INCONCLUSIVE`

The selector consumes the BF-374 opportunity decision and the exact BF-373 strategic-eligibility report used to construct it. For `COUNTER_AVAILABLE`, the eligible market-rank list must match exactly before selection is allowed.

Selection uses only two governed market dimensions, lexicographically:

1. lowest `excessValue` beyond the governed fairness-boundary adjustment;
2. if excess is equal, lowest absolute `assetValue` intervention.

The existing BF-369 deterministic tail ordering by adjustment type, asset type, and asset ID is **not** a decision tie-breaker. Those fields may make evidence output stable, but they may not decide which player or pick Butler selects.

If two or more top eligible candidates have exactly equal `excessValue` and `assetValue`, BF-375 returns `AMBIGUOUS` and exposes the tied market ranks. It does not select the first deterministic list entry.

If the opportunity is `NO_COUNTER`, BF-375 returns `NO_SELECTION`. If the opportunity is `INCONCLUSIVE`, BF-375 remains `INCONCLUSIVE`.

When selection is `SELECTED`, `trade counter-decision` prints the selected market rank, adjustment direction, asset identity/type, excess value, asset value, required market-value change, and resulting fairness gap.

Selection still does **not** emit a `COUNTER` action. It identifies the uniquely best governed single-asset candidate after strategic eligibility; turning that selected asset into an action or outbound proposal remains a separate policy boundary.

## Runtime short-circuit

`trade counter-decision` evaluates live v5 first.

Strategic candidate discovery/vetting/eligibility is only executed when v5 evidence is complete and the selected perspective receives `REJECT`. `ACCEPT`, `HOLD`, and incomplete v5 evidence do not perform unnecessary candidate analysis.

Candidate selection is then evaluated from the BF-374 opportunity decision. A non-available opportunity cannot produce an asset selection.

## Frozen boundaries

BF-375 does not modify:

- Trade Recommendation v5;
- the v5 action vocabulary;
- the 5% market-fairness policy;
- BF-369 market candidate ranking;
- BF-372 bilateral strategic veto semantics;
- BF-373 clear-only eligibility semantics;
- BF-374 counter-opportunity semantics.

BF-375 also does not:

- emit `COUNTER`;
- build multi-asset counter packages;
- use player/pick identity as a selection tie-breaker;
- introduce hidden weighting or strategic score blending.

A future true `COUNTER` action or proposal-construction contract remains a separate product decision.
