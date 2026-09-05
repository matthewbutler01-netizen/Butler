# Governed league lineup-capture ranking stability methodology

Butler may evaluate the **sensitivity of an already-governed lineup-capture ranking** to removal of one common comparable week at a time.

This methodology answers one narrow question:

> how much do the governed lineup-capture ranks and rates change when any one contributing common comparable week is deliberately omitted?

The implemented v1 answer is deterministic sensitivity evidence, not statistical confidence. It does **not** authorize confidence intervals, probabilities, p-values, bootstrap claims, manager-consistency grades, tiers, or causal interpretations.

## Methodology status

This document is normative for the implemented v1 ranking-stability surface completed by BF-505 and BF-506.

V1 uses **leave-one-common-week-out sensitivity** only.

The governing decisions are:

- the baseline source is the already-governed league lineup-capture ranking report;
- the baseline ranking must itself be `AVAILABLE`;
- the baseline must contain at least **5 common comparable weeks** so every one-week omission still leaves BF-500's four-week ranking floor intact;
- every baseline common comparable week is omitted exactly once;
- every perturbation retains the exact same repository team universe;
- every perturbation recalculates every team's started points, potential points, points gap, and lineup-capture rate from the nested governed weekly source evidence over the retained weeks;
- every perturbation applies the same six-decimal rate precision, competition-ranking tie policy, and no-secondary-tiebreaker rules as the baseline ranking;
- no perturbation may silently drop a team or skip an omitted-week scenario;
- team-level rank and rate movement are exposed as deterministic sensitivity ranges and counts; and
- no qualitative `stable`, `unstable`, `high confidence`, or manager-quality label is inferred.

## Governed terms

Permitted terms are:

- **lineup-capture ranking stability evidence**;
- **leave-one-common-week-out sensitivity**; and
- **rank sensitivity range** for the deterministic rank range observed across required perturbations.

The artifact is not manager-ranking confidence, a manager consistency score, a reliability grade, a statistical confidence interval, a probability of true rank, or predictive rank stability.

## Required baseline source

The source of truth is the governed lineup-capture ranking report produced under:

```text
league-season-lineup-capture-ranking-v1-common-universe-min-4-weeks-competition-ranking-no-manager-attribution
```

The complete baseline ranking report remains nested and inspectable in the stability artifact.

The stability implementation does not independently select teams, rebuild the league common universe from fresh repository reads, use independently scoped season rates, or form a different baseline ranking. All perturbation evidence derives from the nested common-universe source already attached to the baseline ranking report.

## Five-week stability floor

BF-500 permits a baseline lineup-capture ranking when at least four common comparable weeks exist. A four-week baseline is not sufficient for leave-one-week-out sensitivity because removing any contributing week would leave only three retained weeks.

```text
minimum common weeks for baseline ranking = 4
minimum common weeks for leave-one-week-out stability = 5
```

A four-week baseline ranking may therefore be available while v1 stability evidence is unavailable.

The five-week requirement is a governance precondition for the perturbation design. It is not a statistical-significance threshold and does not imply five weeks are sufficient to measure manager skill or future rank reliability.

## Perturbation universe

Let the baseline ordered common comparable week list be:

```text
W = [w1, w2, ... wn]
```

where `n >= 5`.

V1 creates exactly `n` deterministic perturbation scenarios. For each common week `wi`:

```text
retained weeks(i) = W excluding wi
```

The omitted week is a deliberate sensitivity perturbation. It is **not** reclassified as blocked, incomplete, or non-comparable source evidence.

Scenarios are presented in ascending omitted-week order.

## No fabricated common-universe report

The baseline common-universe analyzer represents the exact all-team intersection of `COMPARABLE_COMPLETE` weeks. The stability analyzer does not fabricate a new common-universe report whose week list intentionally removes a week that remains genuinely comparable.

Instead it consumes the governed baseline ranking, reads the nested weekly points-gap evidence, deliberately omits one baseline common week, recalculates scenario totals/rates over the retained weeks, and applies the existing ranking policy directly to those scenario rates.

This keeps sensitivity perturbation separate from evidence comparability.

## Fixed team universe and fail-closed behavior

Every perturbation contains every repository team represented by the baseline ranking source.

Butler does not omit a team whose perturbation rate becomes unavailable, substitute an independently scoped rate, assign an unavailable team last place, alter the baseline team universe, widen another team's retained week set, or skip a perturbation scenario.

If any required scenario cannot produce an available governed normalized rate for every baseline team, the **entire v1 stability summary is unavailable**. Failed scenario evidence may remain inspectable, but Butler does not summarize only the successful perturbations.

## Scenario recalculation

For team `T` and omitted week `wi`:

```text
scenario started(T, wi) = sum of recalculated started points over retained weeks(i)
scenario potential(T, wi) = sum of retrospective potential points over retained weeks(i)
scenario points gap(T, wi) = sum of governed potential-minus-started gap over retained weeks(i)
scenario lineup capture rate(T, wi) = scenario started(T, wi) / scenario potential(T, wi)
```

The normalized rate uses the same governed precision as the baseline ranking:

```text
scale: 6 decimal places
rounding: HALF_UP
```

Existing zero/negative-point normalization rules remain in force. If any scenario team rate is unavailable, the complete stability artifact becomes unavailable rather than publishing a partial sensitivity summary.

## Scenario ranking policy

Every fully available perturbation uses the BF-500 ranking policy:

- higher governed six-decimal lineup-capture rate receives the better position;
- exact six-decimal ties share the same rank;
- standard competition ranking is used (`1, 2, 2, 4`);
- no secondary metric breaks a tie; and
- team-name order is used only for deterministic presentation inside a shared tie.

Scenario ranking never uses the two-decimal CLI percentage and never introduces points-gap, coverage, team-ID, pairwise-contrast, head-to-head, or manager-identity tie-breakers.

## Stability states

The implemented v1 states are:

```text
AVAILABLE
UNAVAILABLE_BASELINE_RANKING
UNAVAILABLE_INSUFFICIENT_COMMON_WEEKS_FOR_PERTURBATION
UNAVAILABLE_PERTURBATION_TEAM_RATE
```

`UNAVAILABLE_BASELINE_RANKING` applies when the nested BF-500 ranking is not available.

`UNAVAILABLE_INSUFFICIENT_COMMON_WEEKS_FOR_PERTURBATION` applies when the baseline ranking is available but contains fewer than five common comparable weeks.

`UNAVAILABLE_PERTURBATION_TEAM_RATE` applies when the baseline has at least five common weeks but at least one required scenario cannot produce available normalized rates for every baseline team.

When stability is unavailable, Butler publishes no partial team stability summary.

## Required scenario evidence

Each omitted-week scenario retains enough evidence to reproduce its result, including:

- omitted common week;
- ordered retained common weeks;
- scenario state;
- every baseline team ID/name;
- scenario started points;
- scenario potential points;
- scenario points gap;
- governed six-decimal scenario lineup-capture rate when available; and
- scenario lineup-capture rank when the complete scenario ranking is available.

The scenario team universe exactly matches the baseline ranking team universe.

## Team deterministic sensitivity summary

When every required perturbation is available, Butler may expose for each team:

- baseline lineup-capture rank and rate;
- perturbation scenario count;
- ordered distinct perturbation ranks observed;
- best and worst perturbation rank;
- rank sensitivity range width (`worst - best`);
- maximum absolute rank movement from baseline;
- count of perturbations equal to the baseline rank;
- count of perturbations different from the baseline rank;
- minimum and maximum perturbation lineup-capture rate; and
- maximum absolute perturbation rate movement from baseline.

All are deterministic summaries of the complete governed perturbation set.

## No qualitative stability tier

V1 does not convert movement into labels such as stable, mostly stable, volatile, fragile, high confidence, low confidence, reliable, or unreliable.

For example, Butler may report:

```text
baseline rank: 2
perturbation ranks observed: [1, 2, 3]
rank sensitivity range: 1..3
maximum absolute rank movement: 1
baseline-rank unchanged scenarios: 4 of 7
```

It may also report the direct deterministic fact that a rank was unchanged in every required perturbation. Neither statement is a prediction, confidence claim, or manager-consistency judgment.

## Rate sensitivity is not a confidence interval

Minimum and maximum perturbation rates are observed leave-one-week-out sensitivity values. They are not a confidence interval, prediction interval, credible interval, sampling distribution, population estimate, or probability statement.

## Baseline rank remains authoritative

BF-504 does not replace, average, smooth, or revise the BF-500 baseline lineup-capture rank.

Perturbation ranks answer a different question: how the ordinal result changes when one contributing common week is deliberately removed.

Butler does not create a consensus or stability-adjusted rank from average, median, modal, best-case, or worst-case perturbation ranks.

## No league-wide stability score

V1 does not calculate average rank movement across teams, median rank movement, a league stability percentage, stability leaderboard, volatility tier, manager-consistency score, or a composite of baseline rank and sensitivity movement.

## Historical-startability and attribution boundaries

Leave-one-week-out sensitivity does not repair historical-startability limitations in the underlying potential-lineup evidence.

Every baseline and perturbation scenario remains governed retrospective lineup-capture evidence built from observed provider configuration and dated production/eligibility evidence. It does not establish every injury, inactive status, suspension, acquisition timing, platform lock, or other decision-time constraint.

A rank that changes little across the perturbations is therefore not proof of manager consistency, reliability, skill, quality, fault, intent, decision discipline, or causal responsibility.

## No statistical-confidence claim

BF-504 does not authorize probabilistic uncertainty modeling. V1 does not publish confidence intervals, p-values, bootstrap intervals, permutation-test significance, Bayesian posterior probabilities, standard errors, probability of a true rank, rank confidence scores, or predictive stability claims.

Leave-one-common-week-out sensitivity is a deterministic stress test, not a statistical estimator of an unknown true manager rank.

## Implemented policy identifier

BF-505 uses:

```text
league-season-lineup-capture-ranking-stability-v1-leave-one-common-week-out-min-5-common-weeks-deterministic-no-confidence-claim
```

The metric scope identifies deterministic leave-one-common-week-out sensitivity of the governed lineup-capture ranking with no manager attribution and no statistical-confidence claim.

## Implemented v1 surface

BF-505 and BF-506 completed the implementation authorized by this methodology.

Analyzer:

```text
LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer
```

CLI:

```text
butler league season-lineup-capture-ranking-stability-evidence <league-id> <season>
```

The implementation preserves the required layering:

1. the governed BF-500 ranking report is the baseline source;
2. at least five common weeks are required for the sensitivity artifact;
3. each baseline common week is omitted exactly once;
4. every scenario uses the same baseline team universe and retained-week set across teams;
5. scenario totals/rates are recalculated from nested governed weekly evidence;
6. BF-500 six-decimal competition ranking is reapplied without secondary tie-breakers;
7. any unavailable required scenario withholds the complete stability summary;
8. deterministic team rank/rate sensitivity summaries remain directly tied to the complete perturbation set; and
9. the baseline rank remains authoritative.

**Stop boundary:** the v1 deterministic stability implementation is complete. Any qualitative stability tier, manager consistency/reliability label, statistical confidence interval or probability claim, bootstrap/permutation/Bayesian inference, stability-adjusted rank, league-wide stability score, recommendation, causal interpretation, skill/fault attribution, coverage-adjusted composite, or cross-league stability comparison requires a new governed methodology decision before implementation.
