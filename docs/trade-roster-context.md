# Trade Roster Context

Butler's trade roster-context layer is a descriptive evidence surface. It attaches the two participating fantasy teams' existing neutral team profiles and raw production context to the governed player-only trade evidence package.

It does not decide whether either team is a contender or rebuilder, grade positional need, weight evidence, or recommend accepting or rejecting a trade.

## Command

`butler trade supporting-evidence <league-id> <season> <side-a-player-ids> <side-b-player-ids> [source]`

The existing command now presents four independent evidence groups:

1. persisted player market values and coverage,
2. governed 5% market-fairness and market-edge context,
3. governed player age-outlook supporting flags,
4. neutral team roster and production context for each trade side.

No new trade command or parser surface is required.

## Trade-team identity

Roster context is only meaningful when each trade package belongs to one fantasy team. The roster-context analyzer therefore requires:

- every Side A player to resolve to the same fantasy team,
- every Side B player to resolve to the same fantasy team,
- Side A and Side B to resolve to different fantasy teams.

A package spanning multiple teams fails closed rather than receiving misleading team context.

This is stricter than the lower-level market-value comparison, which can compare arbitrary non-overlapping rostered player lists. The stricter rule applies only when Butler claims to provide team-specific roster context.

## Neutral team profile

For each participating team, Butler reuses the existing `LeagueCompositeTeamProfileAnalyzer`. The trade market-value source and team-profile market-value source must match.

The profile exposes descriptive quantities including:

- usable player value,
- usable draft-pick value,
- total usable asset value,
- starter-value share,
- top-asset and top-three asset concentration,
- concentration index,
- positional player counts,
- positional market-value coverage,
- positional usable value,
- top-one, top-two, and top-three positional value.

These numbers are not converted into `STRONG`, `WEAK`, `NEED`, `SURPLUS`, contender, or rebuilder labels.

## Production context

For each participating team, Butler also reuses the existing `LeagueProductionContextAnalyzer` for the trade evidence season.

The output preserves raw production context and coverage, including position-level totals for:

- games played,
- passing yards and touchdowns,
- interceptions,
- rushing yards and touchdowns,
- receptions,
- receiving yards and touchdowns,
- fumbles lost.

Raw production is not converted into fantasy points, rankings, grades, or recommendation weights.

## Existing trade evidence remains authoritative

Adding roster context does not alter:

- persisted player values,
- value coverage,
- signed A-B value difference,
- the symmetric market-value gap,
- the governed 5% fairness classification,
- market-edge direction,
- supporting age-outlook flags.

Roster and production evidence are additional dimensions beside those results, not inputs that silently rewrite them.

## Interpretation boundary

The current roster-context layer does **not** provide:

- `CONTENDER` or `REBUILDER` posture,
- positional `NEED` or `SURPLUS` labels,
- a team-strength score,
- a roster-fit score,
- a trade winner,
- `ACCEPT`, `REJECT`, or `COUNTER`,
- hidden weighting between market value, age, production, depth, or draft capital.

Those behaviors require separately governed policies because they introduce thresholds and value judgments that are not implied by the raw evidence.
