# Combined FLEX/SUPERFLEX pressure

Butler classifies one combined flexible-slot pressure tier from neutral FLEX/SUPERFLEX coverage evidence. The tier remains league-relative evidence, but the live v4 trade recommendation now consumes it as a governed evidence gate and, for `FLEXIBLE_PRESSURE` teams, as eligibility for a legal post-trade flexible-coverage material-loss veto.

## Governed policies

- Flexible-slot eligibility: `trade-flexible-slot-eligibility-v1-explicit-lineup`
- Flexible-slot coverage: `flexible-slot-coverage-v1-direct-reserved-max-value`
- Flexible-slot pressure: `flexible-slot-pressure-v1-combined-relative-quartiles`
- Flexible post-trade coverage loss: `trade-flexible-coverage-loss-v1-post-trade-legal-lineup`
- Protected-value materiality: `trade-protected-value-materiality-v1-25-percent-loss`
- Live recommendation: `trade-recommendation-v4-market-first-flexible-material-loss-veto`
- Live strategic veto: `trade-strategic-veto-v3-material-protected-value-plus-flexible-coverage-loss`

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

Under the live v4 recommendation contract, unavailable flexible-pressure evidence makes the recommendation `INCONCLUSIVE`. Butler does not silently fall back to the earlier v3 recommendation path.

If the league has no FLEX or SUPERFLEX slots, teams receive `NO_FLEXIBLE_REQUIREMENT`. The minimum-team rule does not apply because there is no flexible lineup requirement to classify. This is an available evidence state and does not create a flexible material-loss veto.

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

## Legal post-trade coverage loss

Only a team already classified `FLEXIBLE_PRESSURE` is protected by the flexible material-loss rule.

Butler does not compare outgoing and incoming players position-by-position for this rule. Instead it recomputes the selected team's legal lineup after the trade:

1. start from the current valued roster evidence;
2. remove the selected team's outgoing traded players;
3. add incoming traded players with current governed values;
4. reserve the best legal direct QB/RB/WR/TE starters again;
5. optimize the remaining players across the league's active FLEX and SUPERFLEX slots using the same eligibility and maximum-value coverage logic as the pre-trade analyzer;
6. compare the governed pre-trade flexible coverage value with the recomputed post-trade flexible coverage value.

This permits legal cross-position substitution inside flexible slots. An incoming RB can replace flexible value previously supplied by a WR in ordinary FLEX. SUPERFLEX can use QB/RB/WR/TE. No arbitrary position weights are introduced.

Before measuring loss, Butler verifies that the recomputed pre-trade coverage exactly matches the flexible coverage used to assign the governed pressure tier. A mismatch is rejected rather than allowing the veto to operate on different evidence states.

## Materiality and veto behavior

The same governed 25% materiality boundary used by the existing protected-value veto is reused for flexible coverage:

- exactly `25%` coverage loss: `WITHIN_TOLERANCE`;
- greater than `25%` coverage loss: `MATERIAL_LOSS`.

A flexible `MATERIAL_LOSS` can create the reason:

```text
FLEXIBLE_PRESSURE_MATERIAL_POST_TRADE_COVERAGE_LOSS
```

The strategic veto can only downgrade a directional market recommendation to `HOLD`. Flexible pressure cannot create market direction, reverse the preferred side, or generate a `COUNTER` recommendation.

Flexible veto eligibility is narrow:

- `FLEXIBLE_PRESSURE`: eligible for the legal-coverage material-loss check;
- `FLEXIBLE_BALANCED`: no flexible veto;
- `FLEXIBLE_STRENGTH`: no flexible veto;
- `NO_FLEXIBLE_REQUIREMENT`: no flexible veto;
- `INSUFFICIENT_EVIDENCE`: veto is not evaluated and the live recommendation is `INCONCLUSIVE`.

When multiple strategic reasons exist, the live detector reports them deterministically: future-capital reason first, direct `QB`, `RB`, `WR`, `TE` reasons next, and flexible coverage loss last.

## Direct-position separation

Direct positional protection remains unchanged. If the selected team is under direct `POSITION_PRESSURE` at QB/RB/WR/TE, replacement value for that direct-position veto is still same-position player value only.

Legal cross-position substitution is used only for the combined FLEX/SUPERFLEX coverage calculation. An incoming RB does not replenish a directly pressured WR category merely because that RB can legally fill FLEX.

This separation prevents the flexible model from weakening the existing direct-position protection contract.
