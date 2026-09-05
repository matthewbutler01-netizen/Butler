# Governed lineup capture comparability methodology

Butler may compare lineup-capture evidence across two teams only as a **pairwise descriptive lineup-capture contrast over shared comparable weeks**. This methodology does not authorize manager rankings, grades, tiers, winners, or skill claims.

## Methodology status

This document is normative for the first cross-team lineup-capture contrast implementation.

The governing decision is deliberately narrower than a ranking system:

- Butler may calculate two team-specific lineup-capture rates over the same shared comparable week set;
- Butler may report the signed percentage-point difference between those two rates;
- Butler must expose the shared-week denominator and each team's broader coverage context; and
- Butler must not translate the difference into manager quality, rank, tier, recommendation, fault, intent, or skill.

The existing season lineup-capture rates are **not directly comparable by default** because each team may have been normalized over a different set of comparable weeks. A cross-team contrast must therefore recalculate both sides over one explicitly shared week universe.

## Why existing season rates are insufficient for comparison

A governed team-season lineup-capture rate answers a within-team question:

> Across this team's comparable complete observed roster weeks, what share of governed retrospective potential points was represented by the recalculated started lineups?

Two valid team-season rates may still have materially different evidence support. For example:

- Team A may have 8 comparable weeks out of 9 observed weeks;
- Team B may have 4 comparable weeks out of 9 observed weeks;
- the comparable weeks may not be the same calendar weeks; and
- blocked or incomplete evidence may fall in different parts of the season.

Subtracting those independently scoped season rates would silently compare different evidence windows. V1 cross-team contrast does not permit that shortcut.

## Governed term

The permitted term is **pairwise lineup-capture contrast**.

It means only:

> the difference between two teams' governed retrospective lineup-capture rates calculated over the same shared comparable observed weeks.

The output must not be called:

- manager efficiency difference;
- manager performance difference;
- coaching advantage;
- lineup-management skill difference;
- winner/loser;
- better/worse manager; or
- any similar attribution.

## Scope

A v1 pairwise contrast is available only for two distinct teams in the **same league and same season**.

Cross-league comparison is not authorized by this methodology. Different league scoring, roster construction, starting-slot structure, provider settings, and competitive context would require a separate methodology.

The comparison is directional for presentation only:

```text
Team A contrast Team B = Team A shared-week capture rate - Team B shared-week capture rate
```

Reversing the team order reverses the sign but does not change the evidence.

## Shared comparable week universe

The cross-team week universe is the intersection of weeks where both teams have governed team-season lineup points-gap evidence in `COMPARABLE_COMPLETE` state.

Formally:

```text
shared comparable weeks =
    Team A COMPARABLE_COMPLETE weeks
    INTERSECT
    Team B COMPARABLE_COMPLETE weeks
```

Only those shared weeks may contribute to either side of the pairwise rate calculation.

A week that is comparable for one team but blocked or incomplete for the other team is **not shared comparable** and contributes to neither side of the pairwise contrast.

Butler must preserve enough coverage metadata to show that exclusion rather than silently narrowing the season.

## Shared-week team rates

For each team, recalculate lineup capture from that team's governed weekly points-gap evidence over the same shared week set.

For Team A:

```text
Team A shared-week capture rate =
    Team A total recalculated started points over shared comparable weeks
    /
    Team A total retrospective potential points over shared comparable weeks
```

For Team B:

```text
Team B shared-week capture rate =
    Team B total recalculated started points over shared comparable weeks
    /
    Team B total retrospective potential points over shared comparable weeks
```

The pairwise implementation must not reuse the already-materialized full team-season capture rate unless the implementation proves that the team's full comparable week set exactly equals the shared comparable week set.

The authoritative inputs are the nested governed weekly points-gap reports from each team's season evidence.

## Pairwise contrast formula

When both shared-week rates are available:

```text
pairwise lineup-capture contrast =
    Team A shared-week capture rate - Team B shared-week capture rate
```

The normalized rates remain materialized at the existing v1 precision:

```text
scale: 6 decimal places
rounding: HALF_UP
```

The contrast should also be materialized at scale 6 using `HALF_UP`.

CLI presentation may show the rates and contrast as percentages/percentage points with two decimal places, while retaining the raw started/potential totals and shared-week count.

Example:

```text
Team A shared-week capture: 0.900000 (90.00%)
Team B shared-week capture: 0.825000 (82.50%)
A minus B contrast:          0.075000 (+7.50 percentage points)
```

This arithmetic does **not** establish that Team A had a better manager or made better decisions.

## Required coverage context

Every pairwise contrast report must expose, at minimum:

- league ID and season;
- Team A identity and Team B identity;
- each team's observed roster week count;
- each team's individually comparable-complete week count;
- ordered shared comparable week numbers;
- shared comparable week count;
- Team A shared total recalculated started points;
- Team A shared total retrospective potential points;
- Team A shared total points gap;
- Team A optional shared-week capture rate;
- Team B shared total recalculated started points;
- Team B shared total retrospective potential points;
- Team B shared total points gap;
- Team B optional shared-week capture rate; and
- optional signed pairwise lineup-capture contrast.

The report should also expose enough excluded-week context to establish why individually comparable weeks were not shared when that distinction exists.

## No arbitrary minimum shared-week threshold in v1

V1 does not invent a statistical significance threshold or minimum shared-week sample size.

A one-week shared contrast may be mathematically valid, but its evidence denominator must say exactly one shared comparable week. The system must not imply that such a result is a stable season-long difference.

A future confidence, stability, or sample-sufficiency claim would require a separate statistical methodology.

## Zero and negative point handling

The shared-week rate rules inherit the governed lineup-capture normalization boundary.

For either team, a shared-week rate is unavailable when:

- there are no shared comparable weeks;
- any contributing shared comparable week has negative started points;
- any contributing shared comparable week has negative potential points; or
- that team's total shared retrospective potential points are not positive.

A shared comparable week with zero potential may remain in the shared-week count when the governed source evidence allows it, but it receives no independent weekly rate and does not make a team-level shared rate available unless the team's total shared potential across all shared weeks is positive.

If either team's shared-week rate is unavailable, the signed pairwise contrast is unavailable. Butler retains the shared-week and raw-points evidence rather than fabricating a comparison value.

## Same-league evidence boundary

Being in the same league and season reduces, but does not eliminate, comparability limitations.

The pairwise analyzer must fail closed if the two shared-week source reports do not preserve compatible governed league configuration/scoring/eligibility policy boundaries for a week being compared.

The implementation must not repair mismatched provenance, substitute another week, impute missing evidence, or widen the week universe.

## Historical-startability limitation remains

Shared calendar weeks do not reconstruct each rostered player's real-world historical startability.

The governed potential lineup still uses observed provider configuration and dated production/eligibility evidence. It does not automatically know whether a player was injured, suspended, inactive, locked, newly acquired, dropped, or otherwise practically unavailable at the manager's decision point.

Therefore even a perfectly aligned shared-week contrast remains a comparison of **governed retrospective lineup-capture evidence**, not a causal estimate of manager decision quality.

## What v1 can defend

V1 may support statements such as:

- `Across 6 shared comparable observed weeks, Team A captured 90.00% of governed retrospective potential points and Team B captured 82.50%. The descriptive lineup-capture contrast was +7.50 percentage points for Team A.`
- `The teams had only 2 shared comparable weeks, so the contrast applies only to those two governed observed weeks.`
- `A pairwise contrast is unavailable because the teams have no shared comparable complete weeks.`

Those statements must retain the retrospective/historical-startability boundary in broader presentation.

## What v1 cannot defend

V1 does not permit statements such as:

- `Team A's manager is 7.50 percentage points better.`
- `Team A has the better lineup manager.`
- `Team B's manager cost the team more points.`
- `Team A ranks above Team B in lineup skill.`
- `This proves Team A made better start/sit decisions.`
- `Team A should be ranked first in the league.`

The evidence does not establish those causal or evaluative claims.

## League ranking remains prohibited

This methodology does not authorize constructing all pairwise contrasts and then deriving a league table, ordinal ranking, Elo-like score, tier, percentile, grade, or winner.

Pairwise contrast is not transitive evidence of manager quality. Even if A's descriptive contrast is above B's and B's is above C's under different shared-week intersections, Butler must not infer an A > B > C manager ranking.

A league ranking would require a separate methodology that explicitly resolves:

- one common comparison universe across all teams;
- uneven and missing coverage;
- historical-startability evidence;
- stability/sample requirements;
- scoring/configuration changes over time;
- treatment of zero/negative point weeks;
- statistical uncertainty;
- whether the result is descriptive or causal; and
- what interpretation a rank is actually allowed to carry.

## Proposed policy identifier

A conforming first implementation should use a policy identifier that keeps the shared-week and non-attribution boundaries visible:

```text
team-pair-season-lineup-capture-contrast-evidence-v1-shared-comparable-weeks-no-attribution
```

A metric scope should make clear that the result is a retrospective pairwise contrast over shared governed weeks and is not manager performance.

## Implementation sequence authorized by this methodology

After this specification is accepted, the defensible implementation path is:

1. pairwise team-season lineup-capture contrast analyzer derived only from the two governed team-season points-gap reports;
2. constructor-time invariants that recompute shared-week totals/rates/contrast from nested source evidence;
3. pairwise CLI exposing both teams' raw shared totals, rates, contrast, and shared-week denominator; and
4. help/documentation exposure.

**Stop boundary:** the implementation must stop again before league ranking, tiers, manager grades, recommendations, causal interpretation, skill/fault attribution, statistical confidence claims, or cross-league comparison.