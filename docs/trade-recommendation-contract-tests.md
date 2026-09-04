# Trade recommendation contract tests

The trade recommendation surface is versioned so policy changes remain explicit and auditable. Contract tests retain historical policy identifiers while also locking the identifiers and vocabulary used by the current live recommendation path.

## Locked recommendation identifiers

Historical compatibility layers remain versioned and valid, including:

- `trade-recommendation-v1-conservative-evidence-first`
- `trade-recommendation-v2-market-first-strategic-veto`
- `trade-recommendation-v3-market-first-material-loss-veto`
- `trade-recommendation-v4-market-first-flexible-material-loss-veto`
- `trade-strategic-veto-v1-explicit-weakness-protection`
- `trade-strategic-veto-v2-material-protected-value-loss`
- `trade-strategic-veto-v3-material-protected-value-plus-flexible-coverage-loss`

Current live path:

- `trade-recommendation-v5-market-first-flexible-transition-material-loss-veto`
- `trade-strategic-veto-v4-material-protected-value-plus-flexible-transition-loss`
- `trade-flexible-pressure-transition-v1-post-trade-league-relative`
- `trade-flexible-post-trade-depth-v1-two-team-exchange`
- `trade-flexible-coverage-loss-v1-post-trade-legal-lineup`
- `trade-protected-value-flow-v1-current-valued-assets`
- `trade-protected-value-materiality-v1-25-percent-loss`
- `trade-team-perspective-v1-explicit-owner`

The executable `trade recommendation` command uses v5. The v1-v4 recommendation and v1-v3 strategic-veto contracts remain intact for compatibility and audit history and must not be silently repurposed.

## Locked strategic reason vocabulary

The live strategic detector preserves the prior material-loss reasons and adds one transition reason:

- `LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS`
- `POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS`
- `FLEXIBLE_PRESSURE_MATERIAL_POST_TRADE_COVERAGE_LOSS`
- `FLEXIBLE_MATERIAL_LOSS_TRANSITION_TO_PRESSURE`

Reason ordering remains deterministic: future capital first; direct positional reasons in `QB`, `RB`, `WR`, `TE` order; pre-existing flexible-pressure loss next; transition-to-pressure loss last.

A well-formed assessment cannot contain both flexible reason codes for the same selected team. A team is either already under `FLEXIBLE_PRESSURE` or newly transitions into it.

## Locked team-action vocabulary

- `ACCEPT`
- `REJECT`
- `HOLD`
- `INCONCLUSIVE`

The lower-level veto state vocabulary remains `CLEAR` / `BLOCKED`. When required governed evidence is incomplete, the CLI presents `Strategic veto: NOT_EVALUATED`; that is a presentation state and does not add a third lower-level veto state.

## Locked transition boundary

The live v5 transition rule requires both conditions:

1. the selected team moves from a non-pressure flexible tier into `FLEXIBLE_PRESSURE` after the trade is applied to both trade teams and the league is reranked; and
2. the selected team's legal flexible coverage loss is greater than `25%`.

Exactly `25%` loss remains within tolerance and does not trigger the transition veto.

These tests are compatibility guards. They do not change the evidence gate, perspective mapping, market-first ownership of direction, protected-value aggregation, weighting, side-flipping rules, posture behavior, or introduce `COUNTER` behavior.
