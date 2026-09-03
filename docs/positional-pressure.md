# Lineup-aware positional pressure

Policy: `positional-pressure-v1-lineup-relative-quartiles`.

Butler evaluates QB, RB, WR, and TE independently using the actual direct starter requirements stored from the league's lineup configuration. This is descriptive strategic context, not a trade recommendation.

## Ranking metric

For each core position, Butler sums the market value of only the top **N** valued players on each roster, where **N** is that position's direct starter requirement.

Examples:

- a league with one required QB ranks QB using each team's top one QB value;
- a league with two required RBs ranks RB using each team's top two RB values;
- additional bench depth remains visible as total position value but does not inflate the ranking metric.

No production, age, draft capital, team posture, or other dimension is blended into this value.

## League-relative tiers

With at least four league teams and complete relevant value coverage:

- top 25% -> `POSITION_STRENGTH`
- bottom 25% -> `POSITION_PRESSURE`
- middle -> `POSITION_BALANCED`

Boundary ties are preserved. If the top and bottom boundaries collapse onto the same value, including an all-equal league, teams remain balanced rather than receiving contradictory strength/pressure labels.

## Evidence requirements

For a position with a direct starter requirement, every rostered player at that position must have a current usable value under the selected source/freshness boundary. Missing or stale relevant values make that position `INSUFFICIENT_EVIDENCE` across the league.

A team with zero rostered players at a required position is valid zero-value evidence. Butler does not treat the absence itself as missing data.

A position with no direct starter requirement is `NO_DIRECT_REQUIREMENT`; Butler does not invent positional weakness where the lineup does not directly require the position.

## FLEX and SUPERFLEX

FLEX and SUPERFLEX are retained as separate exposure context:

- FLEX does not get assigned entirely to RB, WR, or TE;
- SUPERFLEX does not get assigned entirely to QB or another position;
- neither changes the direct-starter **N** used by this policy.

This avoids hidden fractional allocation or positional weighting. A future recommendation policy may consider exposure, but it must do so explicitly.

## Unknown lineup slots

Unknown lineup-slot semantics fail closed. Butler preserves the unknown token and does not issue positional-pressure tiers until the slot's meaning is governed or recognized.

## CLI

```text
butler league positional-pressure <league-id> [source] [--minimum-as-of YYYY-MM-DD]
```

The command displays direct starter requirements, FLEX/SUPERFLEX exposure, tier, starter-coverage value, total positional value, and value-coverage counts.

## Decision boundary

`POSITION_PRESSURE` does not mean "must acquire," and `POSITION_STRENGTH` does not mean "should sell." Positional pressure remains one independent strategic dimension. Butler still requires a separately governed policy before combining positional pressure with market fairness, market edge, team posture, future capital, player evidence, or trade direction into `ACCEPT`, `REJECT`, or `COUNTER`.
