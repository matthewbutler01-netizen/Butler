# Combined FLEX/SUPERFLEX pressure

Butler classifies one combined flexible-slot pressure tier from neutral FLEX/SUPERFLEX coverage evidence. The tier remains league-relative evidence. The live v5 trade recommendation consumes it as a governed evidence gate, protects teams already in `FLEXIBLE_PRESSURE` from material legal-coverage loss, and also protects non-pressure teams from materially falling into `FLEXIBLE_PRESSURE` after a trade.

## Governed policies

- Flexible-slot eligibility: `trade-flexible-slot-eligibility-v1-explicit-lineup`
- Flexible-slot coverage: `flexible-slot-coverage-v1-direct-reserved-max-value`
- Flexible-slot pressure: `flexible-slot-pressure-v1-combined-relative-quartiles`
- Flexible post-trade coverage loss: `trade-flexible-coverage-loss-v1-post-trade-legal-lineup`
- Flexible post-trade depth: `trade-flexible-post-trade-depth-v1-two-team-exchange`
- Flexible pressure transition: `trade-flexible-pressure-transition-v1-post-trade-league-relative`
- Protected-value materiality: `trade-protected-value-materiality-v1-25-percent-loss`
- Live recommendation: `trade-recommendation-v5-market-first-flexible-transition-material-loss-veto`
- Live strategic veto: `trade-strategic-veto-v4-material-protected-value-plus-flexible-transition-loss`

The pressure policy consumes the maximum legal flexible coverage value produced after direct QB/RB/WR/TE starters have already been reserved.

## Combined tier

FLEX and SUPERFLEX remain distinct lineup inputs, but their remaining legal coverage is classified as one combined flexible dimension.

This avoids pretending that one specific position necessarily owns a SUPERFLEX slot. A QB may fill SUPERFLEX, but RB/WR/TE may also fill it when legal and higher-valued. Ordinary FLEX remains RB/WR/TE only.

The combined tier does not alter the existing direct QB, RB, WR, or TE pressure tiers.

## League-relative classification

When a league has at least one FLEX or SUPERFLEX slot, complete neutral flexible coverage evidence, and at least four teams, Butler ranks teams by maximum legal flexible coverage value.

The outer-quartile discipline mirrors direct positional pressure:

- top boundary: `FLEXIBLE_STRENGTH`
- middle: `FLEXIBLE_BALANCED`
- bottom boundary: `FLEXIBLE_PRESSURE`

The outer count is `floor(teamCount * 0.25)`.

Boundary values, not arbitrary team ordering, determine the tier. If multiple teams tie at a quartile boundary, every tied team receives the same applicable tier. If the entire league has the same flexible coverage value, every team is `FLEXIBLE_BALANCED` rather than being simultaneously classified as strength and pressure.

## Insufficient and inactive cases

If active flexible-slot coverage evidence is unavailable, the pressure report is unavailable and teams receive `INSUFFICIENT_EVIDENCE`. The upstream insufficiency reason is preserved.

If active flexible slots exist but fewer than four league teams are available, Butler returns `INSUFFICIENT_EVIDENCE` because relative quartile classification is not safe.

Under the live v5 recommendation contract, unavailable flexible-pressure evidence makes the recommendation `INCONCLUSIVE`. Butler does not silently fall back to an earlier recommendation version.

If the league has no FLEX or SUPERFLEX slots, teams receive `NO_FLEXIBLE_REQUIREMENT`. The minimum-team rule does not apply because there is no flexible lineup requirement to classify. This is an available evidence state and creates neither an existing-pressure nor a transition-to-pressure veto.

Team-level flexible-slot counts must agree with the league's FLEX plus SUPERFLEX exposure. Mismatched evidence is rejected instead of being silently ranked.

## Trade context

`TradeFlexibleSlotContextAnalyzer` attaches flexible-pressure evidence to both explicit trade teams.

`TradeFlexibleRecommendationContextAnalyzer` composes the live recommendation inputs from one coordinate-consistent set of evidence: the existing trade positional context, lineup requirements, positional depth, flexible coverage, flexible pressure, and team attachment.

Composition requires agreement on:

- league;
- value source;
- freshness boundary;
- team ID and team name;
- FLEX/SUPERFLEX slot exposure.

Unavailable evidence remains unavailable when attached to a trade. The context layer does not convert missing evidence into a direction or assume a fallback tier.

## Shared post-trade roster mutation

v5 uses one governed roster-mutation algorithm for flexible trade evidence:

1. begin from current governed positional-depth evidence;
2. remove outgoing traded players from the team giving them;
3. add incoming traded players with current governed values;
4. reject missing, stale, non-finite, negative, or freshness-incompatible values rather than guessing;
5. rebuild positional depth deterministically.

The earlier v4 existing-pressure analyzer keeps its selected-team-only compatibility surface, but it now reuses this same mutation algorithm. v5 transition analysis uses the full two-team exchange because league-relative reranking depends on both trade teams after the trade.

## Existing-pressure legal coverage loss

A team already classified `FLEXIBLE_PRESSURE` remains protected by the existing flexible material-loss rule.

Butler does not compare outgoing and incoming players position-by-position for this rule. Instead it recomputes the selected team's legal lineup after the trade:

1. apply the trade to the selected team's governed depth;
2. reserve the best legal direct QB/RB/WR/TE starters again;
3. optimize the remaining players across the league's active FLEX and SUPERFLEX slots using the same eligibility and maximum-value coverage logic as the pre-trade analyzer;
4. compare governed pre-trade flexible coverage value with recomputed post-trade flexible coverage value.

This permits legal cross-position substitution inside flexible slots. An incoming RB can replace flexible value previously supplied by a WR in ordinary FLEX. SUPERFLEX can use QB/RB/WR/TE. No arbitrary position weights are introduced.

Before measuring existing-pressure loss, Butler verifies that recomputed pre-trade coverage matches the flexible coverage used to assign the governed pressure tier. A mismatch is rejected rather than allowing the veto to operate on different evidence states.

## Transition-to-pressure evidence

v5 adds a separate transition analysis for a selected team that is not already `FLEXIBLE_PRESSURE`.

For this analysis Butler:

1. applies the proposed exchange to **both** trade teams;
2. recomputes legal FLEX/SUPERFLEX coverage for the full post-trade league;
3. reruns the same league-relative flexible-pressure classification;
4. finds the selected team's post-trade tier;
5. compares pre-trade and post-trade legal flexible coverage;
6. applies the same governed 25% materiality rule.

Both teams must be updated before reranking. Updating only the selected team is incorrect because the opposite trade team's changed coverage can move the league-relative pressure boundary.

The transition analyzer states are:

- `NO_FLEXIBLE_REQUIREMENT`
- `INSUFFICIENT_EVIDENCE`
- `NO_TRANSITION`
- `TRANSITION_WITHIN_TOLERANCE`
- `MATERIAL_TRANSITION_TO_PRESSURE`

A transition exists only when the selected team's pre-trade tier is not `FLEXIBLE_PRESSURE` and its post-trade tier is `FLEXIBLE_PRESSURE`.

## Materiality and veto behavior

The governed materiality boundary remains **25%**:

- exactly `25%` coverage loss is within tolerance;
- greater than `25%` coverage loss is material.

For a team already in `FLEXIBLE_PRESSURE`, material legal coverage loss can create:

```text
FLEXIBLE_PRESSURE_MATERIAL_POST_TRADE_COVERAGE_LOSS
```

For a team not already in pressure, a newly material move into `FLEXIBLE_PRESSURE` can create:

```text
FLEXIBLE_MATERIAL_LOSS_TRANSITION_TO_PRESSURE
```

The transition reason requires **both** a post-trade move into `FLEXIBLE_PRESSURE` and greater than 25% legal flexible coverage loss. A tier transition at exactly 25% remains non-blocking. A material coverage loss that does not move the team into pressure is also non-blocking under this transition rule.

A valid selected-team assessment cannot trigger both flexible reasons. An already-pressured team is governed by the existing-pressure rule; a non-pressure team can only be governed by the transition rule.

The strategic veto can only downgrade a directional market recommendation to `HOLD`. Flexible evidence cannot create market direction, reverse the preferred side, or generate a `COUNTER` recommendation.

## Tier behavior under v5

- `FLEXIBLE_PRESSURE`: eligible for existing-pressure legal-coverage protection.
- `FLEXIBLE_BALANCED`: not an existing protected area, but may trigger transition protection if the trade moves the team into `FLEXIBLE_PRESSURE` with greater than 25% loss.
- `FLEXIBLE_STRENGTH`: not an existing protected area, but may trigger transition protection under the same two-condition rule.
- `NO_FLEXIBLE_REQUIREMENT`: no flexible veto.
- `INSUFFICIENT_EVIDENCE`: veto is not evaluated and the live recommendation is `INCONCLUSIVE`.

When multiple strategic reasons exist, the live detector reports them deterministically: future-capital reason first; direct `QB`, `RB`, `WR`, `TE` reasons next; existing flexible-pressure coverage loss next; transition-to-pressure loss last.

## Direct-position separation

Direct positional protection remains unchanged. If the selected team is under direct `POSITION_PRESSURE` at QB/RB/WR/TE, replacement value for that direct-position veto is still same-position player value only.

Legal cross-position substitution is used only for combined FLEX/SUPERFLEX coverage. An incoming RB does not replenish a directly pressured WR category merely because that RB can legally fill FLEX.

This separation prevents the flexible model from weakening the existing direct-position protection contract.
