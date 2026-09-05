# Governed league lineup-capture ranking stability methodology

Butler may evaluate the **sensitivity of an already-governed lineup-capture ranking** to the removal of one common comparable week at a time.

This methodology addresses one narrow uncertainty question:

> how much would the governed ordinal lineup-capture ranks and rates change if any one contributing common comparable week were omitted from the evidence window?

The v1 answer is deterministic sensitivity evidence, not statistical confidence. It does **not** authorize confidence intervals, probabilities, p-values, bootstrap claims, manager-consistency grades, tiers, or causal interpretations.

## Methodology status

This document is normative for the first ranking-stability implementation authorized after the v1 common-universe lineup-capture ranking.

V1 uses **leave-one-common-week-out sensitivity** only.

The governing decisions are:

- the baseline source is the already-governed league lineup-capture ranking report;
- the baseline ranking must itself be available;
- the baseline must contain at least **5 common comparable weeks** so every one-week omission still leaves the BF-500 four-week ranking floor intact;
- every baseline common comparable week is omitted exactly once;
- every perturbation retains the exact same repository team universe;
- every perturbation recalculates every team's started points, potential points, points gap, and lineup-capture rate from the nested governed weekly source evidence over the retained weeks;
- every perturbation applies the same six-decimal rate precision, competition-ranking tie policy, and no-secondary-tiebreaker rules as the baseline ranking;
- no perturbation may silently drop a team or skip a difficult omitted week;
- team-level rank and rate movement are exposed as deterministic sensitivity ranges and counts; and
- no qualitative `stable`, `unstable`, `high confidence`, or manager-quality label is inferred from those movements.

## Governed term

The permitted terms are:

- **lineup-capture ranking stability evidence**;
- **leave-one-common-week-out sensitivity**; and
- **rank sensitivity range** when referring to the observed deterministic rank range across the governed perturbations.

The artifact must not be called:

- manager-ranking confidence;
- manager consistency score;
- manager reliability grade;
- statistical confidence interval;
- probability of true rank;
- predictive rank stability; or
- any equivalent person-level or probabilistic claim.

## Required baseline source

The source of truth is the governed lineup-capture ranking report produced under:

```text
league-season-lineup-capture-ranking-v1-common-universe-min-4-weeks-competition-ranking-no-manager-attribution
```

The complete baseline ranking report must remain nested and inspectable in the stability artifact.

The stability implementation must not independently select teams, rebuild the league common universe from fresh repository reads, use independently scoped season rates, or form a different baseline ranking.

All perturbation evidence is derived from the nested common-universe source already attached to the baseline ranking report.

## Why v1 requires at least five common weeks

BF-500 permits a baseline lineup-capture ranking when at least four common comparable weeks exist.

A four-week baseline is **not sufficient** for leave-one-week-out rank stability because removing any contributing week would leave only three retained weeks, which is below the governed ranking floor.

Therefore:

```text
minimum common weeks for baseline ranking = 4
minimum common weeks for leave-one-week-out stability = 5
```

A league may legitimately have an available four-week lineup-capture ranking while its v1 ranking-stability evidence is unavailable.

The five-week requirement is a governance precondition for the perturbation design. It is not a statistical significance threshold and does not imply five weeks are enough to measure manager skill or future rank reliability.

## Perturbation universe

Let the baseline ordered common comparable week list be:

```text
W = [w1, w2, ... wn]
```

where `n >= 5`.

V1 creates exactly `n` deterministic perturbation scenarios.

For each common week `wi`:

```text
retained weeks(i) = W excluding wi
```

The omitted week is a deliberate sensitivity perturbation. It is **not** reclassified as blocked, incomplete, or non-comparable source evidence.

The baseline common-universe report remains unchanged. The stability artifact records the omitted sensitivity week and the retained ordered week list separately.

Scenarios are presented in ascending omitted-week order.

## No fabricated common-universe report

The baseline common-universe analyzer is governed to expose the exact all-team intersection of `COMPARABLE_COMPLETE` weeks.

A stability implementation must not fabricate a new common-universe report whose common-week list intentionally removes a week that is still genuinely comparable for all teams. Doing so would violate the source report's constructor invariants and blur the distinction between evidence comparability and sensitivity perturbation.

Instead, the stability analyzer must:

1. consume the governed baseline ranking report;
2. read the nested weekly points-gap evidence from its nested common-universe source;
3. deliberately omit one baseline common week for a sensitivity scenario;
4. recalculate scenario totals and normalized rates over the retained week list; and
5. apply the existing ranking policy directly to those scenario rates.

This keeps perturbation logic separate from the normative meaning of the common-universe evidence artifact.

## Team universe is fixed

Every perturbation contains every repository team represented by the baseline ranking source.

Butler must not:

- omit a team whose perturbation rate becomes unavailable;
- substitute an independently scoped season rate;
- assign the unavailable team last place;
- alter the baseline team universe;
- widen one team's retained week set independently; or
- skip the perturbation scenario.

If any one-week-out scenario cannot produce an available governed normalized rate for every baseline team, the **entire v1 stability summary is unavailable**.

The failed scenario evidence may remain inspectable, but Butler must not summarize only the successful perturbations as though the omitted scenario did not exist.

## Scenario recalculation

For team `T` and omitted common week `wi`, Butler recalculates directly from the retained governed weekly points-gap reports:

```text
scenario started(T, wi) =
    sum of recalculated started points over retained weeks(i)

scenario potential(T, wi) =
    sum of retrospective potential points over retained weeks(i)

scenario points gap(T, wi) =
    sum of governed potential-minus-started gap over retained weeks(i)
```

When normalization is available:

```text
scenario lineup capture rate(T, wi) =
    scenario started(T, wi) / scenario potential(T, wi)
```

The rate uses the same governed precision as the baseline ranking:

```text
scale: 6 decimal places
rounding: HALF_UP
```

The implementation should recompute the sums from the retained nested weekly evidence rather than materializing a new common-universe source artifact.

## Scenario normalization availability

The existing lineup-capture normalization rules remain in force.

A scenario team rate is unavailable when the retained evidence cannot produce a valid governed common-universe normalized rate, including when the retained total potential points are not positive.

Because baseline ranking already requires nonnegative contributing common-week points, v1 does not reinterpret negative evidence during perturbation. It simply preserves the existing governed normalization boundary.

If any scenario team rate is unavailable, the complete stability artifact state becomes unavailable rather than publishing a partial sensitivity summary.

## Scenario ranking policy

Every fully available perturbation uses the same rank policy as BF-500:

- higher governed six-decimal lineup-capture rate receives the better numeric position;
- exact six-decimal ties share the same rank;
- standard competition ranking is used (`1, 2, 2, 4`);
- no secondary metric breaks a tie; and
- team-name order is used only for deterministic presentation inside a shared tie.

Scenario ranks must not use the two-decimal CLI percentage.

Scenario ranking must not introduce points-gap, coverage, team-ID, pairwise-contrast, head-to-head, or manager-identity tie-breakers.

## Stability availability states

A conforming v1 implementation should make stability availability explicit.

Recommended states are:

```text
AVAILABLE
UNAVAILABLE_BASELINE_RANKING
UNAVAILABLE_INSUFFICIENT_COMMON_WEEKS_FOR_PERTURBATION
UNAVAILABLE_PERTURBATION_TEAM_RATE
```

`UNAVAILABLE_BASELINE_RANKING` applies whenever the nested BF-500 ranking state is not `AVAILABLE`.

`UNAVAILABLE_INSUFFICIENT_COMMON_WEEKS_FOR_PERTURBATION` applies when the baseline ranking is available but contains fewer than five common comparable weeks.

`UNAVAILABLE_PERTURBATION_TEAM_RATE` applies when the baseline has at least five common weeks but at least one required leave-one-week-out scenario cannot produce available normalized rates for every baseline team.

When stability is unavailable, Butler must not publish a partial set of team stability summaries.

## Required scenario evidence

For each omitted common week, the stability report should retain enough evidence to reproduce the perturbation result.

At minimum each scenario should expose:

- omitted common week number;
- ordered retained common week numbers;
- scenario state;
- every baseline team ID and name;
- scenario recalculated started points;
- scenario retrospective potential points;
- scenario points gap;
- scenario governed six-decimal lineup-capture rate when available; and
- scenario lineup-capture rank when the complete scenario ranking is available.

The scenario team universe must exactly match the baseline ranking team universe.

## Team-level deterministic sensitivity summary

When every required perturbation is available, Butler may summarize each team's observed rank movement across the complete leave-one-week-out scenario set.

For team `T`, v1 may expose:

- baseline lineup-capture rank;
- baseline governed lineup-capture rate;
- perturbation scenario count;
- ordered distinct perturbation ranks observed;
- best perturbation rank observed;
- worst perturbation rank observed;
- rank sensitivity range width:

```text
worst perturbation rank - best perturbation rank
```

- maximum absolute rank movement from baseline:

```text
max(abs(perturbation rank - baseline rank))
```

- number of perturbations whose rank equals the baseline rank;
- number of perturbations whose rank differs from the baseline rank;
- minimum perturbation lineup-capture rate;
- maximum perturbation lineup-capture rate; and
- maximum absolute perturbation rate movement from the baseline rate.

All calculations are deterministic summaries of the governed perturbation set.

## No qualitative stability tier in v1

V1 does **not** define a threshold that converts rank movement into labels such as:

- stable;
- mostly stable;
- volatile;
- fragile;
- high confidence;
- low confidence; or
- reliable/unreliable.

For example, Butler may report:

```text
baseline rank: 2
perturbation ranks observed: [1, 2, 3]
rank sensitivity range: 1..3
maximum absolute rank movement: 1
baseline-rank unchanged scenarios: 4 of 7
```

It may not convert that evidence into `stable manager`, `medium confidence`, or another categorical judgment without a new methodology.

A boolean statement that a rank was unchanged across every required perturbation may be reported only as a direct deterministic fact, for example:

```text
rank unchanged in all 7 leave-one-week-out scenarios: true
```

That fact is not a guarantee of future stability or manager consistency.

## Rate sensitivity is not a confidence interval

The minimum and maximum perturbation rates form an observed deterministic sensitivity range over the specified one-week omissions.

They are **not**:

- a confidence interval;
- a prediction interval;
- a credible interval;
- a sampling distribution;
- a population parameter estimate; or
- a probability statement.

Butler must label them as leave-one-week-out sensitivity values or perturbation ranges, not statistical uncertainty bounds.

## Baseline rank remains authoritative

BF-504 does not replace, average, smooth, or revise the baseline BF-500 lineup-capture rank.

The baseline rank remains the governed ordinal artifact for the full common comparable week set.

Perturbation ranks answer a different question: how the ordinal result changes when one contributing common week is deliberately removed.

Butler must not create a new consensus rank from:

- average perturbation rank;
- median perturbation rank;
- modal perturbation rank;
- best-case rank;
- worst-case rank; or
- another stability-adjusted ordinal score.

Any replacement or stability-adjusted ranking requires separate methodology.

## No league-wide stability score

V1 may expose team-level deterministic movement summaries and the complete scenario evidence, but it does not authorize a league-wide aggregate stability score.

It must not calculate or materialize:

- average rank movement across teams;
- median rank movement;
- league stability percentage;
- stability leaderboard;
- volatility tier;
- manager-consistency score; or
- a composite of baseline rank and sensitivity movement.

The first implementation should keep each team's sensitivity evidence directly connected to its baseline rank and scenario observations.

## Historical-startability limitation remains

Leave-one-week-out sensitivity does not repair the historical-startability limitation in the underlying potential-lineup evidence.

Every baseline and perturbation scenario still reflects governed retrospective lineup-capture evidence built from observed provider configuration and dated production/eligibility evidence. It does not automatically establish injuries, inactives, suspensions, acquisition timing, platform locks, or every other decision-time constraint.

Therefore a rank that changes little across leave-one-week-out scenarios is still not proof of manager skill, decision quality, or causal responsibility.

## No manager attribution

V1 stability evidence applies to the governed **lineup-capture rank**, not the person managing the team.

Butler must not infer:

- manager consistency;
- manager reliability;
- manager skill;
- manager quality;
- blame or credit;
- decision discipline; or
- coaching stability.

The artifact remains evidence about sensitivity of a retrospective metric ordering.

## No statistical-confidence claim

BF-504 does not authorize probabilistic uncertainty modeling.

The first implementation must not publish:

- confidence intervals;
- p-values;
- bootstrap intervals;
- permutation-test significance;
- Bayesian posterior probabilities;
- probability that a team is truly rank 1;
- probability one team is truly better than another;
- standard errors;
- rank confidence scores; or
- predictive stability claims.

Leave-one-common-week-out sensitivity is a deterministic stress test, not a statistical estimator of an unknown true manager rank.

## What v1 can defend

V1 may support statements such as:

- `The baseline lineup-capture ranking used 7 common comparable weeks. Butler recomputed 7 leave-one-common-week-out scenarios, each retaining 6 weeks.`
- `Team Alpha's baseline lineup-capture rank was 2. Across all 7 one-week-out perturbations, its rank ranged from 1 to 3.`
- `Team Beta's lineup-capture rank remained 4 in all 6 required perturbations.`
- `The league had an available four-week baseline ranking, but v1 stability evidence was unavailable because removing one week would violate the four-week ranking floor.`
- `One required perturbation produced an unavailable normalized team rate, so Butler withheld the complete stability summary rather than skipping that scenario.`

## What v1 cannot defend

V1 does not permit statements such as:

- `Team Alpha has a 90% chance of really being rank 1.`
- `Team Beta's manager is highly consistent.`
- `A rank range of 1 to 2 proves the ranking is statistically reliable.`
- `This is a 95% confidence interval for manager rank.`
- `Team Gamma is unstable because its rank moved by two positions.`
- `The leave-one-week-out average rank should replace the baseline rank.`
- `The stability evidence proves which manager makes better decisions.`

## Proposed policy identifier

A conforming first implementation should use:

```text
league-season-lineup-capture-ranking-stability-v1-leave-one-common-week-out-min-5-common-weeks-deterministic-no-confidence-claim
```

The metric scope should state that the artifact is deterministic leave-one-common-week-out sensitivity of the governed lineup-capture ranking with no manager attribution and no statistical-confidence claim.

## Authorized implementation sequence

After this methodology is accepted, the defensible implementation path is:

1. **BF-505** — league-season ranking-stability analyzer derived only from the governed BF-500 ranking report and its nested common-week evidence;
2. constructor-time invariants that recompute every required omitted-week scenario, scenario rank, and team sensitivity summary from the nested baseline source;
3. **BF-506** — CLI that exposes baseline ranking identity, the five-week stability floor, every omitted-week scenario, and deterministic team rank/rate sensitivity summaries; and
4. **BF-507** — global help and durable documentation closeout.

**Stop boundary:** implementation must stop again before qualitative stability tiers, manager consistency/reliability labels, statistical confidence intervals or probability claims, bootstrap/permutation/Bayesian inference, stability-adjusted ranks, league-wide stability scores, recommendations, causal interpretation, skill/fault attribution, coverage-adjusted composites, or cross-league stability comparison.
