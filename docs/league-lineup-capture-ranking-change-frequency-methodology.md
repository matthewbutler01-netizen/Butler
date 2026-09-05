# Governed league lineup-capture ranking change-frequency methodology

Butler may expose how often an already-governed lineup-capture rank changes across the complete BF-504 leave-one-common-week-out perturbation set.

This methodology answers one narrow question:

> in what fraction of the required governed one-week-out perturbations did a team's lineup-capture rank differ from its BF-500 baseline rank?

The v1 answer is a deterministic frequency measure over the completed perturbation set. It is not statistical probability, confidence, predictive reliability, manager consistency, or a replacement rank.

## Methodology status

This document is normative for the first frequency-aware rank-sensitivity implementation.

V1 authorizes raw team-level rank-change frequency only. It does **not** authorize qualitative frequency tiers such as rare, occasional, frequent, stable, volatile, high confidence, or low confidence.

The source BF-504 ranking-stability report must be `AVAILABLE`. If the governed stability summary is unavailable, no rank-change frequency is published.

## Governed terms

Permitted terms are:

- **lineup-capture rank-change frequency**;
- **changed perturbation scenarios**;
- **unchanged perturbation scenarios**; and
- **observed leave-one-week-out rank-change frequency**.

The artifact must not be called:

- probability the rank is wrong;
- probability the rank will change;
- rank confidence;
- manager consistency;
- manager reliability;
- manager volatility;
- statistical instability; or
- predictive stability.

## Required source artifact

The only authorized source is the governed ranking-stability report produced under:

```text
league-season-lineup-capture-ranking-stability-v1-leave-one-common-week-out-min-5-common-weeks-deterministic-no-confidence-claim
```

The complete source stability report remains nested and inspectable in the frequency artifact.

The implementation must not independently rebuild perturbations, rerank teams, reload a fresh team universe, alter the common-week set, skip a required scenario, or calculate frequency from a partial source summary.

## Availability

A frequency report is available only when the source stability report is `AVAILABLE`.

Recommended states:

```text
AVAILABLE
UNAVAILABLE_SOURCE_STABILITY
```

When unavailable, Butler publishes no partial team frequency rows.

## V1 formula

For team `T`, BF-504 already exposes:

```text
changed scenarios(T)
unchanged scenarios(T)
perturbation scenario count(T)
```

with the invariant:

```text
changed + unchanged = perturbation scenario count
```

V1 rank-change frequency is:

```text
rank-change frequency(T) =
    changed scenarios(T)
    /
    perturbation scenario count(T)
```

The complementary rank-retention frequency is:

```text
rank-retention frequency(T) =
    unchanged scenarios(T)
    /
    perturbation scenario count(T)
```

These two values must sum to exactly `1.000000` at governed materialized precision.

## Precision

Materialized frequency values use:

```text
scale: 6 decimal places
rounding: HALF_UP
```

CLI percentages may display two decimal places using `HALF_UP`, but the governed six-decimal value remains authoritative.

The denominator is always the complete required BF-504 perturbation count. Butler must not reduce the denominator by excluding inconvenient or unavailable scenarios.

## No new sample threshold

BF-504 already requires at least five baseline common weeks and therefore at least five required leave-one-week-out scenarios.

BF-512 introduces no additional minimum scenario threshold.

The five-week BF-504 floor remains a governance requirement for the perturbation design, not statistical significance or proof of reliability.

## Frequency is separate from movement magnitude

BF-508 classifies maximum absolute rank movement as:

```text
0  -> LOW_SENSITIVITY
1  -> MODERATE_SENSITIVITY
2+ -> HIGH_SENSITIVITY
```

BF-512 does not modify that classification.

Movement magnitude and change frequency answer different questions:

- BF-508: how far did the rank move at most?
- BF-512: how often did the rank differ from baseline?

For example, two teams can both be `HIGH_SENSITIVITY` while one changes rank in 1 of 7 scenarios and another changes in 6 of 7.

V1 may show both dimensions side by side, but it must not combine them into a score, confidence tier, adjusted rank, or manager evaluation.

## No qualitative frequency tiers in v1

BF-512 does not define thresholds such as:

```text
0%-20% = low frequency
21%-50% = moderate frequency
51%-100% = high frequency
```

Those cutoffs would be arbitrary without a separately governed rationale.

V1 exposes the raw numerator, denominator, governed decimal frequency, and display percentage only.

Any qualitative frequency classification requires a new methodology decision.

## No probabilistic interpretation

Observed rank-change frequency across deterministic leave-one-week-out scenarios is not an estimate of the probability that the baseline rank is wrong or will change in the future.

For example:

```text
rank changed in 2 of 7 perturbations = 0.285714
```

means only that two of the seven required governed one-week omissions changed the ordinal rank.

It does not mean:

- a 28.57% probability the true rank differs;
- 71.43% confidence in the baseline rank;
- a 28.57% chance of future rank movement; or
- a manager reliability score of 71.43%.

## Baseline rank remains authoritative

The BF-500 baseline rank over the full common-week set remains the governed ordinal artifact.

Rank-change frequency is context around that rank. Butler must not calculate:

- frequency-adjusted rank;
- confidence-weighted rank;
- rank penalty or bonus;
- consensus rank;
- expected rank;
- average perturbation rank; or
- any replacement ordinal score.

## No sensitivity leaderboard

V1 does not authorize sorting teams by rank-change frequency to create a league leaderboard.

The frequency artifact should preserve the source BF-504 team-summary order.

Butler must not calculate:

- most/least stable team;
- most/least stable manager;
- league average change frequency;
- frequency percentile;
- volatility standings;
- league stability score; or
- a combined magnitude-frequency ranking.

## Historical-startability and attribution boundaries

BF-512 inherits every limitation from the lineup-capture, common-universe, ranking, and BF-504 stability layers.

A low observed change frequency does not prove historical decision-time player availability was fully reconstructed and does not establish manager consistency, reliability, skill, quality, or decision discipline.

A high observed change frequency does not establish manager volatility, poor decisions, fault, or causal responsibility.

The subject is the sensitivity of a retrospective governed ordinal artifact, not the person managing the team.

## What v1 can defend

Examples of permitted statements:

- `Team Alpha's lineup-capture rank changed in 2 of 7 required leave-one-week-out scenarios, for an observed rank-change frequency of 0.285714.`
- `Team Beta's rank remained unchanged in all 6 required perturbations, so its observed rank-change frequency was 0.000000.`
- `Team Gamma was HIGH_SENSITIVITY by maximum movement and changed rank in 1 of 7 perturbations; magnitude and frequency are reported separately.`
- `The source ranking-stability artifact was unavailable, so Butler did not publish rank-change frequencies.`

## What v1 cannot defend

V1 does not permit statements such as:

- `Team Alpha has 71% confidence in its rank.`
- `Team Beta's manager is highly consistent.`
- `Team Gamma has a 14% chance of changing rank next week.`
- `The frequency score should lower Team Gamma's baseline rank.`
- `The team with the lowest frequency is the most reliable manager.`
- `These frequencies are directly comparable across leagues as a manager-quality measure.`

## Proposed policy identifier

A conforming first implementation should use:

```text
league-season-lineup-capture-ranking-change-frequency-v1-complete-leave-one-out-changed-over-total-no-confidence-no-manager-attribution
```

The metric scope should state that the artifact is deterministic observed rank-change frequency across the complete governed BF-504 perturbation set, with no statistical-confidence claim and no manager attribution.

## Authorized implementation sequence

After this methodology is accepted, the defensible implementation path is:

1. **BF-513** — rank-change-frequency analyzer derived only from the governed BF-504 stability report;
2. constructor-time invariants that recompute counts and six-decimal frequencies from the nested source stability summary;
3. **BF-514** — CLI exposing changed/unchanged counts, scenario denominator, governed frequency, display percentage, and BF-508 magnitude class as separate context; and
4. **BF-515** — global help and durable documentation closeout.

**Stop boundary:** implementation must stop again before qualitative frequency tiers, combined magnitude-frequency scores or matrices, manager consistency/reliability labels, statistical confidence or probability claims, predictive modeling, frequency-adjusted ranks, sensitivity leaderboards, league-wide sensitivity scores, recommendations, causal interpretation, skill/fault attribution, coverage-adjusted composites, or cross-league manager comparison.
