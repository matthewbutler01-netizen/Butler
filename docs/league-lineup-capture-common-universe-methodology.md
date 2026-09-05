# Governed league lineup-capture common-universe methodology

Butler may place lineup-capture evidence for every team in one league into a **neutral common-universe comparison table** only when every displayed normalized team rate is recalculated over the exact same governed common comparable week set.

This methodology does **not** authorize rankings, tiers, winners, manager grades, league averages, pairwise league matrices, or skill claims.

## Methodology status

This document is normative for the implemented v1 league-wide common-universe lineup-capture surface.

The governing decision is intentionally narrower than a leaderboard:

- Butler identifies one common comparable week universe shared by every repository team in the league;
- Butler recalculates each team's lineup-capture rate over that exact common week universe;
- Butler presents all teams together in neutral repository team-name order;
- Butler exposes the common-week denominator and each team's broader evidence coverage; and
- Butler does not sort, rank, tier, grade, average, declare a winner, or attribute the result to manager skill, fault, intent, or decision quality.

The existing team-season capture rates and pairwise contrast rates are **not** inputs to the league-wide normalized table. League-wide common-universe rates are recalculated directly from each team's governed weekly points-gap evidence over one league-wide shared week set.

## Governed term

The permitted term is **league common-universe lineup-capture table**.

It means only:

> a neutral side-by-side presentation of each league team's governed retrospective lineup-capture evidence recalculated over the same weeks that are comparable complete for every repository team in that league and season.

The output must not be called manager-efficiency standings, lineup-management rankings, a manager leaderboard, coaching rankings, a manager-performance table, or a best/worst-manager table.

## League and team scope

V1 is scoped to exactly one league and one season.

The team universe is **every team returned by the governed repository for that league**. Butler does not exclude a team merely because excluding that team would increase the number of common comparable weeks.

If fewer than two repository teams exist, league-wide comparison is unavailable. The underlying team evidence remains inspectable, but Butler does not present a one-team artifact as a league comparison.

Cross-league comparison remains outside this methodology.

## Common comparable week universe

For each repository team, Butler obtains governed team-season lineup points-gap evidence.

For team `T`:

```text
C(T) = week numbers whose team-season points-gap state is COMPARABLE_COMPLETE
```

For repository teams `T1 ... Tn`:

```text
league common comparable weeks = C(T1) INTERSECT C(T2) INTERSECT ... INTERSECT C(Tn)
```

Only weeks in that exact intersection contribute to any normalized team row.

A week comparable for all but one repository team is not a common league week and contributes to none of the normalized rows.

Butler does not substitute another week, impute missing evidence, drop the missing team, use a majority-team threshold, widen one team's row independently, or combine independently scoped season rates.

## Cross-team governed provenance compatibility

Being `COMPARABLE_COMPLETE` independently for every team is necessary but not sufficient for a common league week.

For every candidate common week, the implemented analyzer requires all team points-gap reports to preserve the same governed comparison boundary, including:

- league and season/week identity;
- league-configuration evidence date;
- roster-evidence observation date;
- production-coverage evidence date;
- production source URI;
- scoring policy;
- solver policy;
- eligibility policy; and
- starting-slot count.

A mismatch fails closed. The implementation does not repair provenance, substitute evidence, remove a team, or reinterpret the week.

## Team row calculation over the common universe

For each repository team `T`, Butler recalculates common-universe totals from nested governed weekly points-gap reports:

```text
common started(T) = sum of recalculated started points over league common weeks
common potential(T) = sum of retrospective potential points over league common weeks
common points gap(T) = sum of governed potential-minus-started gap over league common weeks
```

When normalization is available:

```text
common lineup capture rate(T) = common started(T) / common potential(T)
```

V1 uses the existing lineup-capture precision:

```text
scale: 6 decimal places
rounding: HALF_UP
```

The implementation always recalculates from nested weekly evidence. It does not reuse the already-materialized independently scoped full-season rate as a shortcut.

## Required row and report context

The implemented report exposes league ID/name, season, repository team count through the row list, common-universe state, ordered common comparable week numbers, and one team row per repository team.

Each team row exposes:

- team ID and team name;
- the complete governed team-season points-gap source report;
- observed roster week count;
- individually comparable-complete week count;
- individually comparable but excluded-from-common week numbers;
- common comparable week count;
- common total recalculated started points when common weeks exist;
- common total retrospective potential points when common weeks exist;
- common total points gap when common weeks exist;
- common lineup-capture rate state; and
- optional common lineup-capture rate.

Every row uses the same ordered league common comparable week list.

Constructor-time invariants recompute the all-team intersection and every row from the nested governed sources. Fabricated common weeks, reordered team rows, altered totals, or altered normalized rates are rejected.

## Presentation order is neutral

Rows remain in the repository's existing team-name order.

The table does not sort by lineup-capture rate, points gap, started points, potential points, coverage, or another computed value.

Repository team-name order is presentation order only and is not labeled or interpreted as a ranking.

## No cross-team arithmetic in v1

The common-universe table shows each team's independently recalculated row over the same week set, but it does not calculate or materialize:

- ordinal rank;
- percentile;
- tier;
- z-score;
- score relative to league average;
- league average or median lineup-capture rate;
- combined league numerator/denominator;
- distance from best or worst team;
- pairwise difference matrix;
- pairwise wins/losses;
- Elo-like rating; or
- composite manager score.

A human may observe that displayed descriptive values differ. Butler does not convert that observation into an evaluative league artifact.

## Coverage remains explicit and separate

The common comparable week count is a league-level evidence denominator, not a quality adjustment.

Each row also retains broader observed and individually comparable counts plus the individually comparable weeks excluded from the all-team intersection.

Butler does not blend the difference between individual coverage and common coverage into a penalty, bonus, confidence score, or adjusted capture rate.

No repository team is dropped to manufacture a larger common denominator.

## No arbitrary minimum common-week threshold in v1

V1 does not invent a minimum common-week sample size or statistical-significance threshold.

A one-week common table may be mathematically available, but the CLI states exactly one common comparable week. It does not imply a stable season-long ordering or manager-quality conclusion.

If there are zero common comparable weeks, normalized league comparison is unavailable. Butler does not fall back to independently scoped season rates.

A future sample-sufficiency, stability, confidence, or uncertainty claim requires a separate methodology.

## Zero and negative point handling

The governed lineup-capture normalization rules apply independently to each team row over the common week universe.

A team's common lineup-capture rate is unavailable when:

- there are no league common comparable weeks;
- any contributing common week for that team has negative started points;
- any contributing common week for that team has negative potential points; or
- that team's total common retrospective potential points are not positive.

A common week with zero potential may remain in the common-week count when governed source evidence permits it, but it does not create a fabricated weekly percentage.

When common weeks exist, raw common started/potential/gap totals remain visible even if normalization is withheld for negative or zero-potential conditions.

If one team's normalized rate is unavailable, Butler does not rank, substitute, or impute that row.

## Historical-startability limitation remains

A league-wide common calendar week does not reconstruct each rostered player's real-world historical startability.

The governed potential lineup reflects observed provider configuration and dated production/eligibility evidence. It does not automatically establish injuries, inactives, suspensions, acquisition timing, platform locks, or other practical decision-time availability constraints.

Therefore the common-universe table remains **governed retrospective lineup-capture evidence**, not a causal estimate of manager ability or decision quality.

## What v1 can defend

V1 may support statements such as:

- `All 12 teams had 6 common comparable observed weeks. Each displayed lineup-capture rate was recalculated over those same six weeks.`
- `Team Alpha captured 91.20% of governed retrospective potential points over the league's 6 common comparable weeks.`
- `The league had no common comparable weeks across every repository team, so Butler did not publish normalized common-universe rates.`
- `Team Beta had 9 individually comparable weeks, but only 6 belonged to the all-team common universe; the other three did not contribute to the table rate.`

## What v1 cannot defend

V1 does not permit statements such as:

- `Team Alpha ranks first in lineup management.`
- `Team Alpha has the best manager.`
- `Team Beta is the third-most efficient manager.`
- `The league average manager efficiency is 87.4%.`
- `Team Alpha is 6.2 points above league average.`
- `This table proves which managers make the best start/sit decisions.`
- `The lowest capture team is the worst manager.`

## Ranking remains a separate policy decision

A common-universe table removes one major comparability problem: teams are no longer normalized over different week sets.

It does **not** solve the remaining problems required for an ordinal manager ranking, including historical startability, practical decision-time availability, sample stability, minimum evidence requirements, statistical uncertainty, interpretation of small differences, sufficiency of points-opportunity normalization, causal attribution to the manager, and what a rank is actually allowed to claim.

The neutral common-universe table must not be treated as a hidden leaderboard.

## Policy identifier

The implemented analyzer uses:

```text
league-season-lineup-capture-common-universe-evidence-v1-all-repository-teams-common-comparable-weeks-neutral-no-ranking
```

Its metric and presentation scopes state that the artifact is retrospective, all-team common-universe, repository-team-name-ordered evidence with no manager attribution, ranking, or league arithmetic.

## Implemented v1 command surface

BF-497 and BF-498 completed the implementation authorized by this methodology:

```text
butler league season-lineup-capture-common-universe-evidence <league-id> <season>
```

The implementation preserves the intended layering:

1. every repository team's source is its governed team-season points-gap report;
2. common weeks are the exact intersection of every repository team's `COMPARABLE_COMPLETE` week set;
3. cross-team provenance compatibility is required for every contributing common week;
4. every row is recalculated from nested weekly points-gap evidence over that same common set;
5. individually comparable but non-common weeks remain visible per team;
6. zero common weeks and insufficient league size remain explicitly unavailable;
7. rows remain in repository team-name order; and
8. the CLI contains no rank, league-average/median, benchmark-difference, pairwise-matrix, or manager-score column.

**Stop boundary:** the v1 common-universe implementation is complete. Any ordinal rank, tier, percentile, league average/median comparison, benchmark-relative score, pairwise league matrix, winner/loser label, manager grade, recommendation, causal interpretation, skill/fault attribution, statistical confidence claim, coverage-adjusted composite, or cross-league comparison requires a new governed methodology decision before implementation.
