# Governed league lineup-capture common-universe methodology

Butler may place lineup-capture evidence for every team in one league into a **neutral common-universe comparison table** only when every displayed normalized team rate is recalculated over the exact same governed common comparable week set.

This methodology does **not** authorize rankings, tiers, winners, manager grades, league averages, pairwise league matrices, or skill claims.

## Methodology status

This document is normative for the first league-wide common-universe lineup-capture implementation.

The governing decision is intentionally narrower than a leaderboard:

- Butler may identify one common comparable week universe shared by every repository team in the league;
- Butler may recalculate each team's lineup-capture rate over that exact common week universe;
- Butler may present all teams together in neutral repository team-name order;
- Butler must expose the common-week denominator and each team's broader evidence coverage; and
- Butler must not sort, rank, tier, grade, average, declare a winner, or attribute the result to manager skill, fault, intent, or decision quality.

The existing team-season capture rates and pairwise contrast rates are **not** inputs to the league-wide normalized table. League-wide common-universe rates must be recalculated directly from each team's governed weekly points-gap evidence over one league-wide shared week set.

## Governed term

The permitted term is **league common-universe lineup-capture table**.

It means only:

> a neutral side-by-side presentation of each league team's governed retrospective lineup-capture evidence recalculated over the same weeks that are comparable complete for every repository team in that league and season.

The output must not be called:

- manager efficiency standings;
- lineup-management rankings;
- manager leaderboard;
- coaching rankings;
- manager performance table;
- best/worst manager table; or
- any similar evaluative or ordinal framing.

## League and team scope

A v1 common-universe table is scoped to exactly one league and one season.

The team universe is **every team returned by the governed repository for that league**. Butler must not exclude a team merely because excluding that team would increase the number of common comparable weeks.

This rule prevents evidence selection from changing based on which teams make the table look more complete.

If fewer than two repository teams exist, a league-wide comparison is unavailable. Butler may retain the underlying team evidence, but it must not present the result as a league comparison.

Cross-league comparison remains outside this methodology.

## Common comparable week universe

For each repository team, Butler obtains the governed team-season lineup points-gap evidence.

For team `T`, define:

```text
C(T) = week numbers whose team-season points-gap state is COMPARABLE_COMPLETE
```

For repository teams `T1 ... Tn`, the league-wide candidate common universe is:

```text
league common comparable weeks = C(T1) INTERSECT C(T2) INTERSECT ... INTERSECT C(Tn)
```

Only weeks in that exact intersection may contribute to any normalized team row in the league table.

A week comparable for eleven teams but blocked or incomplete for the twelfth team is not a common league week and contributes to **none** of the normalized rows.

Butler must not:

- substitute another week;
- impute the missing team's evidence;
- drop the missing team;
- use a majority-team threshold;
- widen one team's row independently; or
- combine independently scoped season rates.

## Cross-team governed provenance compatibility

Being `COMPARABLE_COMPLETE` independently for every team is necessary but not sufficient for a common league week.

For each candidate common week, all team source reports must preserve compatible governed boundaries for the comparison, including the same:

- league configuration evidence date;
- roster-evidence observation date boundary used for the week;
- production coverage evidence date;
- production source URI;
- scoring policy;
- solver policy;
- eligibility policy; and
- starting-slot structure/count.

If a candidate common week cannot satisfy the governed cross-team provenance boundary, Butler must fail closed for league-wide normalized comparison rather than silently treating that week as comparable.

The implementation may preserve the underlying team reports for inspection, but it must not repair or reinterpret mismatched provenance.

## Team row calculation over the common universe

For every repository team, Butler recalculates common-universe totals directly from that team's nested weekly governed points-gap reports over the league common comparable week set.

For team `T`:

```text
common started(T) =
    sum of recalculated started points for T over league common comparable weeks

common potential(T) =
    sum of retrospective potential points for T over league common comparable weeks

common points gap(T) =
    sum of governed points gap for T over league common comparable weeks
```

When normalization is available:

```text
common lineup capture rate(T) =
    common started(T) / common potential(T)
```

The rate uses the existing governed lineup-capture precision:

```text
scale: 6 decimal places
rounding: HALF_UP
```

The full-season team capture rate must not be reused unless the implementation proves that the team's full comparable week set exactly equals the league common comparable week set. The preferred implementation is to recalculate from nested weekly evidence in all cases.

## Required table row context

Every team row must expose, at minimum:

- team ID;
- team name;
- observed roster week count from the governed source;
- individually comparable-complete week count;
- individually comparable but excluded-from-common week numbers;
- common comparable week count;
- common total recalculated started points when common weeks exist;
- common total retrospective potential points when common weeks exist;
- common total points gap when common weeks exist;
- common lineup-capture rate state; and
- optional common lineup-capture rate.

Every row uses the **same ordered league common comparable week list**.

The table must also expose the league ID, league name, season, repository team count, and ordered common comparable week numbers.

## Presentation order is neutral

Rows must remain in the repository's existing team-name order.

The table must not sort by:

- lineup-capture rate;
- points gap;
- started points;
- potential points;
- coverage;
- alphabetical rank derived from a metric tie-break; or
- any computed comparison value.

Repository team-name order is presentation order only and must not be labeled or interpreted as a ranking.

## No cross-team arithmetic in v1

The common-universe table may show each team's independently recalculated row over the same week set, but v1 does not authorize additional league arithmetic.

The table must not calculate or materialize:

- ordinal rank;
- percentile;
- tier;
- z-score;
- score relative to league average;
- league average lineup-capture rate;
- median lineup-capture rate;
- combined league numerator/denominator;
- distance from best or worst team;
- pairwise difference matrix;
- wins/losses from pairwise contrasts;
- Elo-like rating; or
- composite manager score.

A human can observe that displayed descriptive numbers differ. Butler must not convert that observation into an evaluative league artifact without another governed methodology decision.

## Common coverage remains separate from individual coverage

The common comparable week count is a league-level evidence denominator, not a quality adjustment.

Each team also retains its broader observed and individually comparable counts. For example:

```text
league common comparable weeks: 5
Team A individually comparable: 8 of 9 observed
Team B individually comparable: 6 of 9 observed
...
```

All normalized league-table rows still use only the same five common weeks.

Butler must not blend the difference between individual coverage and common coverage into a penalty, bonus, confidence score, or adjusted capture rate.

## No arbitrary minimum common-week threshold in v1

V1 does not invent a minimum common-week sample size or statistical significance threshold.

A one-week common table may be mathematically available, but the output must state exactly one common comparable week. It must not imply a stable season-long ordering or manager-quality conclusion.

If there are zero common comparable weeks, normalized league comparison is unavailable. Butler must not fall back to each team's independently scoped season rate in the same table.

A future sample-sufficiency, stability, confidence, or uncertainty claim requires a separate methodology.

## Zero and negative point handling

The existing governed lineup-capture normalization rules apply independently to each team row over the common week universe.

A team's common lineup-capture rate is unavailable when:

- there are no league common comparable weeks;
- any contributing common week for that team has negative started points;
- any contributing common week for that team has negative potential points; or
- that team's total common retrospective potential points are not positive.

A common week with zero potential may remain in the common-week count when the governed source permits it, but it does not create a fabricated weekly percentage.

If one team's normalized rate is unavailable, Butler may still retain the common raw evidence and other teams' independently valid row states, but it must not rank, substitute, or impute the unavailable row.

## Historical-startability limitation remains

A league-wide common calendar week does not reconstruct each rostered player's real-world historical startability.

The governed potential lineup still reflects observed provider configuration and dated production/eligibility evidence. It does not automatically establish injuries, inactives, suspensions, acquisition timing, platform locks, or other practical decision-time availability constraints.

Therefore the common-universe table remains a comparison of **governed retrospective lineup-capture evidence**, not a causal estimate of manager ability or decision quality.

## What v1 can defend

V1 may support statements such as:

- `All 12 teams had 6 common comparable observed weeks. Each displayed lineup-capture rate was recalculated over those same six weeks.`
- `Team Alpha captured 91.20% of governed retrospective potential points over the league's 6 common comparable weeks.`
- `The league had no common comparable weeks across every repository team, so Butler did not publish normalized league-table rates.`
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

It does **not** solve the remaining problems required for an ordinal manager ranking, including:

- historical startability and practical decision-time availability;
- sample stability and minimum evidence requirements;
- statistical uncertainty;
- interpretation of differences that may be very small;
- whether points-opportunity normalization alone is sufficient for manager evaluation;
- whether all common weeks should carry equal interpretive weight beyond the points-denominator arithmetic;
- causal attribution to the manager; and
- what a rank is actually allowed to claim.

The neutral common-universe table therefore must not be treated as a hidden leaderboard.

## Proposed policy identifier

A conforming first implementation should use:

```text
league-season-lineup-capture-common-universe-evidence-v1-all-repository-teams-common-comparable-weeks-neutral-no-ranking
```

The metric/presentation scope must state that the artifact is retrospective, all-team common-universe, neutral team-name-order evidence with no manager attribution or ranking.

## Implementation sequence authorized by this methodology

After this specification is accepted, the defensible implementation path is:

1. league-season common-universe lineup-capture evidence analyzer derived only from every repository team's governed team-season points-gap report;
2. invariants that prove the repository team universe, all-team common week intersection, cross-team provenance compatibility, per-team common totals, normalization states, and neutral team-name order;
3. league common-universe CLI that renders the common week denominator and one neutral row per repository team without sorting or cross-team arithmetic; and
4. help/documentation exposure.

**Stop boundary:** implementation must stop again before any ordinal rank, tier, percentile, league average/median comparison, pairwise league matrix, winner/loser label, manager grade, recommendation, causal interpretation, skill/fault attribution, statistical confidence claim, coverage-adjusted composite, or cross-league comparison.
