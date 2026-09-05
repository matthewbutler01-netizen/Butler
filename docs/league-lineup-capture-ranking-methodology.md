# Governed league lineup-capture ranking methodology

Butler may assign an **ordinal lineup-capture rank** inside one league and one season only from the already-governed all-repository-team common-universe lineup-capture evidence.

This methodology authorizes a narrow descriptive rank of the governed common-universe lineup-capture metric. It does **not** authorize a manager-quality ranking, manager-efficiency score, skill estimate, winner/loser label, tier, grade, recommendation, causal conclusion, or cross-league comparison.

## Methodology status

This document is normative for the first league lineup-capture ranking implementation.

The ranking decision is intentionally constrained:

- every ranked team must come from the exact repository team universe already represented by the governed common-universe report;
- every ranked team must use the same all-team common comparable week set;
- the common week set must contain at least four weeks before any ordinal rank is published;
- every repository team must have an available governed common-universe lineup-capture rate;
- ranks are based only on the governed common-universe lineup-capture rate at the existing six-decimal materialized precision;
- exact ties at that governed precision share the same rank;
- Butler must preserve raw common started/potential/gap totals and coverage context alongside rank; and
- Butler must not translate the rank into manager quality, intent, fault, skill, or decision quality.

## Governed term

The permitted term is **lineup-capture rank** or **common-universe lineup-capture rank**.

It means only:

> the ordinal position of a team's governed retrospective lineup-capture rate relative to the other repository teams in the same league and season, when every team is measured over the same sufficiently large governed common comparable week set.

The artifact must not be named or summarized as:

- manager rank;
- manager-efficiency ranking;
- coaching rank;
- lineup-management skill rank;
- best/worst manager ranking;
- decision-quality standings; or
- any equivalent person-level evaluative label.

## Required source artifact

The ranking source of truth is the governed league common-universe lineup-capture report produced under:

```text
league-season-lineup-capture-common-universe-evidence-v1-all-repository-teams-common-comparable-weeks-neutral-no-ranking
```

The ranking implementation must not independently reconstruct team seasons, rescore players, recalculate legal lineups, form a new team universe, widen one team's week set, drop a team, or reuse independently scoped full-season rates.

The entire common-universe source report should remain nested and inspectable in the ranking report so the ordinal artifact cannot detach from its evidence boundary.

## Ranking availability

A league lineup-capture ranking is available only when all of the following are true:

1. at least two repository teams are present;
2. the common-universe source state is available;
3. there are at least **4 common comparable weeks**;
4. every repository team row has an available common-universe lineup-capture rate; and
5. every ranked row remains identity-consistent with the nested common-universe source.

If any requirement fails, the **entire ranking is unavailable**.

Butler must not publish a partial ranking that silently omits an unavailable team.

## Four-week governance floor

V1 uses a minimum of four common comparable weeks before publishing ordinal positions.

This is a **governance floor**, not a claim of statistical significance, reliability, confidence, or predictive stability. The purpose is to prevent a one-, two-, or three-week descriptive sample from being promoted into an ordinal league artifact.

The threshold does not mean four weeks are statistically sufficient to measure manager skill. Butler still makes no manager-skill claim at four weeks, ten weeks, or a full season under this methodology.

Changing the minimum common-week threshold requires a methodology revision rather than an implementation shortcut.

## Rank metric

The only metric authorized for v1 rank assignment is each team's governed common-universe lineup-capture rate:

```text
common lineup capture rate = common started points / common potential points
```

The ranking implementation must use the already-materialized governed rate at:

```text
scale: 6 decimal places
rounding: HALF_UP
```

It must **not** rank on the two-decimal CLI percentage.

It must not rank on:

- raw points gap;
- raw started points;
- raw potential points;
- observed-week coverage;
- individually comparable-week coverage;
- number of excluded weeks;
- pairwise contrasts;
- league average or median distance;
- a composite of capture and coverage; or
- another derived metric.

Raw points and coverage remain visible as evidence context but are not tie-breakers.

## Ordering direction

Higher common-universe lineup-capture rate receives the better numeric ordinal position.

For example:

```text
0.920000 -> ahead of 0.880000
0.880000 -> ahead of 0.810000
```

This establishes metric ordering only. It does not establish that the higher-ranked team's manager is better, more skilled, less at fault, or more deserving of credit.

## Tie policy

Teams whose governed six-decimal common-universe lineup-capture rates compare equal share the same rank.

V1 uses standard competition ranking:

```text
rates: 0.950000, 0.900000, 0.900000, 0.850000
ranks: 1,        2,        2,        4
```

No secondary metric breaks a tie.

In particular, Butler must not break ties using:

- raw points gap;
- started points;
- potential points;
- broader coverage;
- team ID;
- manager identity;
- head-to-head results; or
- pairwise lineup-capture contrast.

Within a tied rank, repository team-name order is permitted only as deterministic presentation order. It is not a hidden tie-breaker and must not change the shared ordinal rank.

## Ranked report ordering

When ranking is available, ranked rows are presented by:

1. ascending numeric rank; then
2. repository team-name order within a shared rank.

This is the first governed lineup artifact authorized to change the neutral team-name order based on a metric.

The source common-universe report itself remains unchanged and retains repository team-name order. The ranking artifact must nest that source rather than mutate its presentation semantics.

## Required ranked-row evidence

Each ranked row must expose, at minimum:

- numeric lineup-capture rank;
- team ID;
- team name;
- governed common-universe lineup-capture rate;
- common total recalculated started points;
- common total retrospective potential points;
- common total points gap;
- common comparable week count;
- observed roster week count;
- individually comparable-complete week count; and
- individually comparable but excluded-from-common week numbers.

The report must expose the league ID/name, season, repository team count, common comparable week numbers, minimum-common-week policy, ranking state, and the complete nested common-universe source report.

## Ranking states

A conforming implementation should make unavailability explicit rather than returning an empty leaderboard without explanation.

V1 states are:

```text
AVAILABLE
UNAVAILABLE_INSUFFICIENT_TEAMS
UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS
UNAVAILABLE_BELOW_MINIMUM_COMMON_WEEKS
UNAVAILABLE_TEAM_COMMON_RATE
```

`UNAVAILABLE_TEAM_COMMON_RATE` applies when the common week universe exists and meets the four-week floor but at least one repository team cannot produce an available normalized common-universe rate under the existing zero/negative-point rules.

When ranking is unavailable, the nested common-universe report remains inspectable.

## No partial ranking

The ranking team universe is all repository teams from the nested source.

If one team is unavailable, Butler must not:

- drop that team;
- rank only the available teams;
- assign the unavailable team last place;
- impute a zero rate;
- reuse that team's independently scoped season rate; or
- widen another team's evidence to compensate.

A ranking that excludes a repository team would answer a different question and requires separate methodology.

## Coverage remains context, not a score component

All ranked teams necessarily use the same common comparable week count.

Broader observed and individually comparable coverage may still differ between teams and remains visible. V1 does not penalize or reward those differences in rank.

There is no coverage-adjusted lineup-capture rank in this methodology.

## No league benchmark arithmetic

V1 rank assignment does not authorize:

- league average lineup-capture rate;
- league median lineup-capture rate;
- standard deviation;
- z-score;
- percentile;
- distance from first place;
- distance from league average;
- top/bottom tier;
- pairwise win/loss matrix;
- Elo-like rating; or
- composite manager score.

The artifact answers only the ordinal question created by sorting the one governed common-universe metric under the availability rules above.

## Historical-startability limitation remains

The common-universe evidence solves the mismatched-week problem but does not reconstruct every player's real-world decision-time availability.

The governed potential lineup still reflects observed provider configuration and dated production/eligibility evidence. It does not automatically establish injuries, inactives, suspensions, acquisition timing, platform locks, or every other condition that may have constrained a real manager's choice.

Therefore a lineup-capture rank remains a rank of **governed retrospective evidence**, not a causal rank of manager ability.

## No statistical-confidence claim

The four-week floor is not a statistical model.

V1 does not publish confidence intervals, p-values, probability that one team is truly better, stability bands, uncertainty scores, or claims that a one-position difference is meaningful.

Two teams may receive adjacent ranks even when their governed rates are extremely close. The raw rates and evidence denominator must remain visible so the ordinal position cannot masquerade as a quantified skill gap.

Any confidence or stability claim requires separate statistical methodology.

## What v1 can defend

V1 may support statements such as:

- `Across the league's 6 common comparable weeks, Team Alpha's governed lineup-capture rate was 0.912000, which produced lineup-capture rank 1 of 12 under the v1 common-universe ranking policy.`
- `Teams Beta and Gamma both had a governed common-universe rate of 0.880000 and therefore shared lineup-capture rank 2.`
- `The league had only 3 common comparable weeks, below the v1 four-week governance floor, so Butler did not publish lineup-capture ranks.`
- `One repository team lacked an available common-universe normalized rate, so Butler withheld the entire league ranking.`

## What v1 cannot defend

V1 does not permit statements such as:

- `Team Alpha has the best manager.`
- `Team Alpha's manager ranks first in lineup skill.`
- `Team Beta is a better manager than Team Gamma.`
- `The last-ranked team has the worst decision-maker.`
- `Rank 1 proves superior start/sit skill.`
- `A two-position difference is statistically meaningful.`
- `This manager deserves credit or blame for the rank.`

## Proposed policy identifier

A conforming first implementation should use:

```text
league-season-lineup-capture-ranking-v1-common-universe-min-4-weeks-competition-ranking-no-manager-attribution
```

The metric scope should state that the artifact is a retrospective ordinal ordering of governed common-universe lineup-capture rates, not manager performance.

## Authorized implementation sequence

After this methodology is accepted, the defensible implementation path is:

1. league-season lineup-capture ranking analyzer derived only from the governed common-universe report;
2. constructor-time invariants that recompute ranking availability, rank order, ties, and ranked rows from the nested source;
3. ranking CLI that exposes rank, raw common evidence, rates, denominator, and explicit non-manager boundary;
4. global help and governed lineup documentation closeout.

**Stop boundary:** implementation must stop again before manager ranking terminology, manager grades, tiers, percentiles, league benchmarks, pairwise win/loss scoring, statistical confidence or stability claims, causal interpretation, skill/fault attribution, recommendations, coverage-adjusted composites, or cross-league ranking.
