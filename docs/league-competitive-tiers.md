# League-relative competitive tiers

Butler's competitive-tier layer is descriptive league context. It does **not** label a team a contender or rebuilder, alter player or pick values, change trade fairness or market edge, or create a trade recommendation.

## Policy

Policy ID: `league-competitive-tier-v1-relative-quartiles`

A league is tierable only when:

- performance evidence covers every fantasy team in the league;
- every team has at least four completed games; and
- the league has at least four teams.

Otherwise every team is reported as `INSUFFICIENT_EVIDENCE` and the report includes the insufficiency reason.

## Ranking

Butler does not create a hidden weighted competitive score. Teams are ranked lexicographically using observed results:

1. win percentage, with ties counting as half a win;
2. points for per game as the first tiebreaker; and
3. point differential per game as the second tiebreaker.

Team identity is never used as a competitive tiebreaker.

## Tier boundaries

For a tierable league, the outer-tier size is `floor(team count * 25%)`, with a minimum of one team. Flooring prevents an outer tier from exceeding 25% merely because of rounding.

- highest-ranked outer group: `FRONT_TIER`
- lowest-ranked outer group: `BACK_TIER`
- all remaining teams: `MIDDLE_TIER`

Boundary ties are preserved. If multiple teams have the same complete ranking tuple at an outer boundary, they receive the same outer tier rather than being split by an arbitrary identifier. This can make an observed outer tier larger than the nominal boundary count. If the front and back boundaries collapse to the same ranking tuple, teams remain `MIDDLE_TIER` rather than receiving contradictory outer labels.

## Early season

The first three completed games are deliberately treated as insufficient evidence. Butler does not infer a competitive tier until all league teams have at least four games in the observed performance snapshot.

## CLI

`butler league performance-evidence <league-id> <season> [source]`

The command prints the persisted performance evidence plus:

- competitive-tier policy ID;
- tier availability and insufficiency reason when applicable;
- points for per game;
- point differential per game; and
- each team's governed competitive tier.

## Interpretation boundary

`FRONT_TIER` does not mean `CONTENDER`.

`BACK_TIER` does not mean `REBUILDER`.

The competitive tier is only one input to any future strategic posture policy. Roster market value, starter/bench structure, positional depth, draft capital, production context, age evidence, and other governed evidence remain independent dimensions until a separate policy explicitly defines how they may be combined.
