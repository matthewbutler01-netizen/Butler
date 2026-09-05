# Governed league lineup-capture ranking change-frequency methodology

Butler may expose how often an already-governed lineup-capture rank changes across the complete BF-504 leave-one-common-week-out perturbation set.

This methodology answers one narrow question:

> in what fraction of the required governed one-week-out perturbations did a team's lineup-capture rank differ from its BF-500 baseline rank?

The v1 answer is a deterministic frequency measure over the completed perturbation set. It is not statistical probability, confidence, predictive reliability, manager consistency, or a replacement rank.

## Methodology status

This document is normative for the **implemented v1** rank-change-frequency surface completed by BF-513 and BF-514.

V1 authorizes raw team-level rank-change frequency only. It does **not** authorize qualitative frequency tiers such as rare, occasional, frequent, stable, volatile, high confidence, or low confidence.

The source BF-504 ranking-stability report must be `AVAILABLE`. If the governed stability summary is unavailable, no rank-change frequency is published.

## Governed terms

Permitted terms are:

- **lineup-capture rank-change frequency**;
- **changed perturbation scenarios**;
- **unchanged perturbation scenarios**; and
- **observed leave-one-week-out rank-change frequency**.

The artifact is not probability the rank is wrong, probability the rank will change, rank confidence, manager consistency, manager reliability, manager volatility, statistical instability, or predictive stability.

## Required source artifact

The only authorized source is the governed ranking-stability report produced under:

```text
league-season-lineup-capture-ranking-stability-v1-leave-one-common-week-out-min-5-common-weeks-deterministic-no-confidence-claim
```

The complete source stability report remains nested and inspectable in the frequency artifact.

The implementation does not independently rebuild perturbations, rerank teams, reload a fresh team universe, alter the common-week set, skip a required scenario, or calculate frequency from a partial source summary.

## Availability

The implemented v1 states are:

```text
AVAILABLE
UNAVAILABLE_SOURCE_STABILITY
```

A frequency report is `AVAILABLE` only when the source stability report is `AVAILABLE`. Otherwise Butler publishes no partial team frequency rows.

## V1 formula

For team `T`, BF-504 exposes:

```text
changed scenarios(T)
unchanged scenarios(T)
perturbation scenario count(T)
```

with the invariant:

```text
changed + unchanged = perturbation scenario count
```

V1 calculates:

```text
rank-change frequency(T) =
    changed scenarios(T)
    /
    perturbation scenario count(T)

rank-retention frequency(T) =
    unchanged scenarios(T)
    /
    perturbation scenario count(T)
```

Materialized frequency values use:

```text
scale: 6 decimal places
rounding: HALF_UP
```

The two values sum to exactly `1.000000` at governed materialized precision. CLI percentages may display two decimals using `HALF_UP`, but the six-decimal values remain authoritative.

The denominator is always the complete required BF-504 perturbation count. Butler never reduces the denominator by excluding inconvenient or unavailable scenarios.

## No new sample threshold

BF-504 already requires at least five baseline common weeks and therefore at least five required leave-one-week-out scenarios. BF-512 adds no new minimum scenario threshold.

The five-week BF-504 floor is a governance precondition for the perturbation design, not statistical significance or proof of reliability.

## Frequency remains separate from movement magnitude

BF-508 classifies maximum absolute rank movement as:

```text
0  -> LOW_SENSITIVITY
1  -> MODERATE_SENSITIVITY
2+ -> HIGH_SENSITIVITY
```

BF-512 does not modify that classification.

The two governed dimensions answer different questions:

- BF-508: how far did the rank move at most?
- BF-512: how often did the rank differ from baseline?

Two teams may therefore share `HIGH_SENSITIVITY` while one changes in 1 of 7 perturbations and another changes in 6 of 7. V1 may show both dimensions side by side, but it does not combine them into a score, confidence tier, adjusted rank, or manager evaluation.

## No qualitative frequency tiers in v1

BF-512 intentionally defines no thresholds such as low, moderate, or high frequency. Generic percentage cutoffs would add arbitrary semantics not justified by the governed evidence.

V1 exposes only:

- changed-scenario numerator;
- unchanged-scenario numerator;
- complete scenario denominator;
- governed six-decimal change frequency;
- governed six-decimal retention frequency; and
- optional two-decimal display percentages.

Any qualitative frequency classification requires a new governed methodology decision.

## No probabilistic interpretation

Observed rank-change frequency across deterministic leave-one-week-out scenarios is not an estimate of the probability that the baseline rank is wrong or will change in the future.

For example:

```text
rank changed in 2 of 7 perturbations = 0.285714
```

means only that two of seven required governed one-week omissions changed the ordinal rank. It does **not** mean 28.57% probability the true rank differs, 71.43% confidence in the baseline rank, 28.57% chance of future movement, or a manager reliability score.

## Baseline rank remains authoritative

The BF-500 baseline rank over the full common-week set remains the governed ordinal artifact. Rank-change frequency is context around that rank.

Butler does not calculate a frequency-adjusted rank, confidence-weighted rank, rank penalty/bonus, consensus rank, expected rank, average perturbation rank, or any replacement ordinal score.

## No sensitivity leaderboard

V1 preserves the source BF-504 team-summary order. Rank-change frequency is not an independent sorting key.

Butler does not calculate or materialize:

- most/least stable team or manager;
- league average change frequency;
- frequency percentile;
- volatility standings;
- league stability/sensitivity score; or
- combined magnitude-frequency ranking.

## Historical-startability and attribution boundaries

BF-512 inherits every limitation from lineup-capture, common-universe, ranking, and BF-504 stability evidence.

A low observed change frequency does not prove historical decision-time availability was fully reconstructed and does not establish manager consistency, reliability, skill, quality, or decision discipline. A high observed frequency does not establish manager volatility, poor decisions, fault, or causal responsibility.

The subject is sensitivity of a retrospective governed ordinal artifact, not the person managing the team.

## Implemented policy identifier

BF-513 uses:

```text
league-season-lineup-capture-ranking-change-frequency-v1-complete-leave-one-out-changed-over-total-no-confidence-no-manager-attribution
```

The implemented metric scope is deterministic observed rank-change frequency across the complete governed BF-504 perturbation set with no statistical-confidence claim and no manager attribution.

## Implemented v1 surface

BF-513 and BF-514 completed the implementation authorized by this methodology.

Analyzer:

```text
LeagueSeasonLineupCaptureRankingChangeFrequencyEvidenceAnalyzer
```

CLI:

```text
butler league season-lineup-capture-ranking-change-frequency-evidence <league-id> <season>
```

The implementation preserves the required layering:

1. BF-504 ranking-stability evidence is the sole source;
2. unavailable source stability yields `UNAVAILABLE_SOURCE_STABILITY` and no team rows;
3. changed and unchanged counts come directly from the complete source team summaries;
4. six-decimal `HALF_UP` change and retention frequencies are recomputed from those counts;
5. change plus retention frequency must equal `1.000000`;
6. BF-508 maximum-movement class is carried only as separate context from the same source summary;
7. source team-summary order is preserved;
8. report construction recomputes the governed frequency rows from the nested source and rejects fabricated or reordered output; and
9. the BF-500 baseline lineup-capture rank remains authoritative.

## What v1 can defend

Examples:

- `Team Alpha's lineup-capture rank changed in 2 of 7 required leave-one-week-out scenarios, for an observed rank-change frequency of 0.285714.`
- `Team Beta's rank remained unchanged in all 6 required perturbations, so its observed rank-change frequency was 0.000000.`
- `Team Gamma was HIGH_SENSITIVITY by maximum movement and changed rank in 1 of 7 perturbations; magnitude and frequency are separate evidence dimensions.`
- `The source ranking-stability artifact was unavailable, so Butler did not publish rank-change frequencies.`

V1 cannot defend probability, confidence, predictive-rank, manager-consistency, manager-reliability, or manager-quality claims from those frequencies.

**Stop boundary:** the v1 raw frequency implementation is complete. Any qualitative frequency tier, combined magnitude-frequency score or matrix, manager consistency/reliability label, statistical confidence or probability claim, predictive modeling, frequency-adjusted rank, sensitivity leaderboard, league-wide sensitivity score, recommendation, causal interpretation, skill/fault attribution, coverage-adjusted composite, or cross-league manager comparison requires a new governed methodology decision before implementation.
