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

`COUNTER_AVAILABLE` is an opportunity state only. It is not a `COUNTER` action and does not identify which candidate should be sent.

## Gate order

The gate is deterministic:

1. If required v5 evidence is incomplete, the counter opportunity is `INCONCLUSIVE`.
2. If complete v5 returns `ACCEPT` or `HOLD`, the result is `NO_COUNTER`. Strategic candidate analysis is not required.
3. Only complete v5 `REJECT` enters the strategic-eligibility gate.
4. If BF-373 strategic eligibility is unavailable for a v5 `REJECT`, the result is `INCONCLUSIVE`.
5. If v5 is `REJECT` but no strategically eligible candidate exists, the result is `NO_COUNTER`.
6. If v5 is `REJECT` and one or more strategically eligible candidates exist, the result is `COUNTER_AVAILABLE`.

Because Trade Recommendation v5 is market-first and strategic vetoes are downgrade-only, a complete v5 `REJECT` represents a directional market disadvantage for the selected team. A strategic veto would downgrade that direction to `HOLD`, not preserve `REJECT`.

## Candidate handling

When `COUNTER_AVAILABLE`, BF-374 exposes only the market ranks of the BF-373 eligible candidates. It deliberately does not:

- choose rank 1;
- re-rank eligible candidates;
- choose between add-to-lower and remove-from-higher;
- select a player or draft pick;
- construct a multi-asset package.

Market rank remains the governed evidence ordering established by BF-369 and preserved by BF-372/BF-373.

## Runtime short-circuit

`trade counter-decision` evaluates live v5 first.

Strategic candidate discovery/vetting/eligibility is only executed when v5 evidence is complete and the selected perspective receives `REJECT`. `ACCEPT`, `HOLD`, and incomplete v5 evidence do not perform unnecessary candidate analysis.

## Frozen boundaries

BF-374 does not modify:

- Trade Recommendation v5;
- the v5 action vocabulary;
- the 5% market-fairness policy;
- BF-369 market candidate ranking;
- BF-372 bilateral strategic veto semantics;
- BF-373 clear-only eligibility semantics.

A future policy that selects one eligible candidate or emits a true `COUNTER` action is a separate product decision and requires its own versioned contract.
