# Combined FLEX/SUPERFLEX pressure

Butler classifies one combined flexible-slot pressure tier from the neutral FLEX/SUPERFLEX coverage evidence. This layer describes league-relative flexible lineup strength only. It does **not** emit a trade recommendation or veto.

## Governed policies

- Flexible-slot eligibility: `trade-flexible-slot-eligibility-v1-explicit-lineup`
- Flexible-slot coverage: `flexible-slot-coverage-v1-direct-reserved-max-value`
- Flexible-slot pressure: `flexible-slot-pressure-v1-combined-relative-quartiles`

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

If the league has no FLEX or SUPERFLEX slots, teams receive `NO_FLEXIBLE_REQUIREMENT`. The minimum-team rule does not apply because there is no flexible lineup requirement to classify.

Team-level flexible-slot counts must agree with the league's FLEX plus SUPERFLEX exposure. Mismatched evidence is rejected instead of being silently ranked.

## Trade context

`TradeFlexibleSlotContextAnalyzer` can attach the descriptive flexible-pressure evidence to both explicit trade teams.

Composition requires agreement on:

- league;
- value source;
- freshness boundary;
- team ID and team name;
- FLEX/SUPERFLEX slot exposure.

Unavailable evidence remains unavailable when attached to a trade. The context layer does not convert missing evidence into a recommendation.

## Recommendation boundary

The live recommendation/material-loss veto does not currently consume `FLEXIBLE_PRESSURE`.

Adding flexible pressure to veto eligibility requires a separately versioned recommendation-policy change and a governed definition of **flexible protected-value loss**.

The existing direct-position material-loss rule cannot be copied mechanically: direct positional protection compares outgoing and incoming value at the same position, while combined FLEX/SUPERFLEX coverage permits legal cross-position substitution and direct-starter reallocation.

Until that protected-loss definition is explicitly governed, flexible pressure remains descriptive evidence only.
