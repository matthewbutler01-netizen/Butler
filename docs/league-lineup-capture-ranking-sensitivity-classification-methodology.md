# Governed league lineup-capture ranking sensitivity classification methodology

Butler may classify the **observed ordinal sensitivity** of an already-governed lineup-capture rank using only the complete BF-504 leave-one-common-week-out stability evidence.

This methodology answers one narrow question:

> how much did a team's governed lineup-capture rank move across the complete required leave-one-common-week-out perturbation set?

The v1 classification is a deterministic descriptive label. It is not statistical confidence, predictive reliability, manager consistency, manager quality, or a replacement ranking.

## Methodology status

This document is normative for the implemented v1 ranking-sensitivity classification surface completed by BF-509 and BF-510.

V1 authorizes exactly three team-level sensitivity classes:

```text
LOW_SENSITIVITY
MODERATE_SENSITIVITY
HIGH_SENSITIVITY
```

The source BF-504 stability report must be `AVAILABLE`. If the governed stability summary is unavailable, no qualitative sensitivity classification is published.

## Governed terms

Permitted terms are:

- **lineup-capture rank sensitivity classification**;
- **low observed rank sensitivity**;
- **moderate observed rank sensitivity**; and
- **high observed rank sensitivity**.

The word **observed** should remain visible in explanatory prose because the classification summarizes the governed perturbation set actually evaluated.

The artifact must not be called or summarized as manager stability, manager consistency, manager reliability, ranking confidence, a confidence tier, probability of true rank, prediction of future rank, manager-quality tier, manager grade, or decision-quality classification.

## Required source artifact

The only authorized source is the implemented governed ranking-stability report produced under:

```text
league-season-lineup-capture-ranking-stability-v1-leave-one-common-week-out-min-5-common-weeks-deterministic-no-confidence-claim
```

The complete BF-504/BF-505 stability report remains nested and inspectable in the classification artifact.

The classification implementation does not rebuild weekly points-gap evidence, reconstruct perturbation scenarios independently, rerank teams from fresh repository reads, use independently scoped season rates, alter the baseline common-week universe, omit a required perturbation, drop a repository team, substitute missing scenario evidence, or classify from a partial stability summary.

## Classification availability

A team-level sensitivity classification is available only when the source stability report is `AVAILABLE` and therefore contains the complete governed leave-one-common-week-out perturbation set for every baseline team.

If the source stability report is unavailable for any reason, the classification artifact is unavailable for the entire league. Butler does not classify a subset of teams from an incomplete source artifact.

The implemented v1 states are:

```text
AVAILABLE
UNAVAILABLE_SOURCE_STABILITY
```

## Classification input

The only v1 classification input is the existing BF-504 team-summary field:

```text
maximum absolute rank movement from baseline
```

For team `T`:

```text
max absolute rank movement(T) =
    max(abs(perturbation rank - baseline rank))
    across every required leave-one-common-week-out scenario
```

V1 classification does not use lineup-capture rate movement, rank-range width as a second scoring input, number or fraction of changed scenarios, raw points gap, started points, potential points, observed-week coverage, broader individually comparable coverage, team-count normalization, baseline rank position, pairwise contrast, manager identity, or another composite score.

Those fields may remain visible as context, but they do not alter the class.

## V1 classification rule

The implemented classification rule is deliberately simple and deterministic:

```text
max absolute rank movement = 0  -> LOW_SENSITIVITY
max absolute rank movement = 1  -> MODERATE_SENSITIVITY
max absolute rank movement >= 2 -> HIGH_SENSITIVITY
```

This is a governance categorization of observed ordinal movement, not a statistical threshold.

The rule is based on integer rank movement because BF-500 defines the governed ordinal artifact and BF-504 measures how that artifact changes under one-week omissions.

## Why v1 does not use percentages

A percentage or normalized rank-movement score would introduce additional assumptions about league size, rank spacing, ties, and what fraction of the league constitutes meaningful movement.

For example, dividing rank movement by `team count - 1` could make the same one-position perturbation appear materially different solely because the league contains a different number of teams. V1 avoids that transformation.

The classification says only how many governed ordinal positions the baseline rank moved at most under the required perturbations. Cross-league comparison of these classes is not authorized.

## Competition-ranking ties remain intact

BF-500 uses standard competition ranking with exact six-decimal ties, for example:

```text
1, 2, 2, 4
```

BF-504 reapplies the same policy in every perturbation. BF-508 consumes the resulting governed ranks exactly as materialized. It does not reinterpret gaps created by competition ranking, compress ranks, switch to dense ranking, or break ties.

A movement from rank 1 to rank 3 is therefore an observed two-position movement under the governed ranking policy and classifies as `HIGH_SENSITIVITY`, even if the jump is caused by a tie appearing or disappearing. The classification is explicitly about sensitivity of the governed ordinal artifact itself.

## Low observed sensitivity

`LOW_SENSITIVITY` means:

```text
maximum absolute rank movement = 0
```

The team's lineup-capture rank was unchanged in every required leave-one-common-week-out scenario.

Permitted wording includes `low observed rank sensitivity`, `the rank did not change in any required one-week-out perturbation`, and `maximum absolute rank movement was 0`.

It must not be paraphrased as statistically stable, reliable, high confidence, robust manager performance, consistent manager, or likely to remain the same in future weeks.

## Moderate observed sensitivity

`MODERATE_SENSITIVITY` means:

```text
maximum absolute rank movement = 1
```

At least one required perturbation moved the team's governed rank by one position, and no perturbation moved it by more than one position.

Permitted wording includes `moderate observed rank sensitivity` and `the rank moved by at most one position across required one-week-out perturbations`.

The label does not imply medium confidence, acceptable reliability, or a manager-quality judgment.

## High observed sensitivity

`HIGH_SENSITIVITY` means:

```text
maximum absolute rank movement >= 2
```

At least one required perturbation moved the team's governed rank by two or more ordinal positions from baseline.

Permitted wording includes `high observed rank sensitivity` and `at least one required one-week-out perturbation moved the rank by two or more positions`.

The label must not be translated into volatile manager, unreliable manager, poor decision-maker, or low-confidence manager ranking.

## No frequency weighting in v1

V1 does not distinguish between a two-position movement that occurs once and a two-position movement that occurs in many perturbations. Both classify as `HIGH_SENSITIVITY` because both have maximum absolute rank movement of at least two.

The existing BF-504 counts of unchanged and changed scenarios remain visible as context but do not alter the class. Combining magnitude and frequency requires a new methodology.

## No rate-movement override

A team can have a small rate movement but a meaningful ordinal movement if teams are closely packed, or a larger rate movement with no rank change if surrounding teams move similarly.

V1 classifies sensitivity of the **rank artifact**, not sensitivity of the underlying rate. Rate movement cannot upgrade or downgrade the ordinal sensitivity class. Rate-sensitivity labels require separate methodology.

## Baseline rank remains authoritative

The classification does not replace or modify the BF-500 baseline lineup-capture rank.

A team may be baseline rank 1 with `HIGH_SENSITIVITY`, or baseline rank 8 with `LOW_SENSITIVITY`. The classification is context about perturbation sensitivity, not an adjusted rank.

Butler does not calculate a sensitivity-adjusted rank, confidence-weighted rank, rank penalty, rank bonus, average perturbation rank, consensus rank, or another replacement ordinal score.

## No league-wide sensitivity standings

V1 classifications are independently attached to each team's governed BF-504 sensitivity summary.

Butler does not sort teams by sensitivity class to create a new leaderboard and does not calculate league average sensitivity, a league sensitivity score, percentage of stable teams, most/least stable manager, sensitivity percentile, volatility standings, or a league-wide composite.

The implemented analyzer preserves the source BF-504 team-summary order. Sensitivity class itself is never an independent ranking key.

## Historical-startability limitation remains

The classification inherits every evidence limitation from the lineup-capture, common-universe, ranking, and leave-one-week-out stability layers.

Low observed sensitivity does not prove that real decision-time player availability was fully reconstructed. High observed sensitivity does not prove a manager made inconsistent or poor decisions.

The artifact remains a classification of retrospective ordinal sensitivity under the governed evidence Butler actually possesses.

## No manager attribution

BF-508 does not authorize person-level evaluation. The classification does not infer manager consistency, manager reliability, manager skill, manager quality, manager discipline, blame or credit, coaching quality, or decision quality.

The subject of the class is the **lineup-capture rank**, not the manager.

## No statistical-confidence claim

`LOW_SENSITIVITY`, `MODERATE_SENSITIVITY`, and `HIGH_SENSITIVITY` are deterministic governance labels over observed leave-one-week-out rank movement.

They are not confidence levels, significance levels, probabilities, posterior beliefs, error bounds, confidence intervals, prediction intervals, bootstrap categories, or reliability estimates.

Butler does not map the classes to percentages such as `90% confidence` or `30% instability`.

## What v1 can defend

V1 may support statements such as:

- `Team Alpha's baseline lineup-capture rank was unchanged across every required one-week-out perturbation, so its observed rank-sensitivity class is LOW_SENSITIVITY.`
- `Team Beta's maximum absolute rank movement was 1, so its observed rank-sensitivity class is MODERATE_SENSITIVITY.`
- `Team Gamma moved from baseline rank 2 to rank 4 in one required perturbation, producing maximum absolute rank movement 2 and HIGH_SENSITIVITY.`
- `The source ranking-stability artifact was unavailable, so Butler did not publish qualitative sensitivity classes.`

## What v1 cannot defend

V1 does not permit statements such as:

- `Team Alpha's manager is highly consistent.`
- `LOW_SENSITIVITY means the ranking is statistically reliable.`
- `Team Beta has medium confidence.`
- `Team Gamma's manager is volatile.`
- `HIGH_SENSITIVITY means the baseline rank is wrong.`
- `Sensitivity class should change the baseline rank.`
- `A low-sensitivity team is better managed than a high-sensitivity team.`
- `These classes are comparable across different leagues.`

## Implemented policy identifier

BF-509 uses:

```text
league-season-lineup-capture-ranking-sensitivity-classification-v1-max-absolute-rank-movement-0-1-2plus-observed-no-confidence-no-manager-attribution
```

The metric scope identifies a deterministic qualitative classification of observed BF-504 ordinal rank sensitivity with no statistical-confidence claim and no manager attribution.

## Implemented v1 surface

BF-509 and BF-510 completed the implementation authorized by this methodology.

Analyzer:

```text
LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer
```

CLI:

```text
butler league season-lineup-capture-ranking-sensitivity-classification-evidence <league-id> <season>
```

The implementation preserves the required layering:

1. the governed BF-504 ranking-stability report is the sole source artifact;
2. the source stability state must be `AVAILABLE`;
3. otherwise the entire classification artifact is `UNAVAILABLE_SOURCE_STABILITY` with no partial team classes;
4. only `maximumAbsoluteRankMovement` determines the class;
5. the exact `0 -> LOW`, `1 -> MODERATE`, `2+ -> HIGH` rule is applied;
6. source team-summary order is preserved and teams are never sorted by sensitivity class;
7. the classification report constructor recomputes expected state and every team classification from the nested source artifact; and
8. the BF-500 baseline lineup-capture rank remains authoritative.

**Stop boundary:** the v1 observed sensitivity-classification implementation is complete. Any manager consistency/reliability label, statistical confidence or probability claim, frequency-weighted sensitivity score, rate-sensitivity class, stability-adjusted rank, sensitivity leaderboard, league-wide sensitivity score, recommendation, causal interpretation, skill/fault attribution, coverage-adjusted composite, or cross-league sensitivity comparison requires a new governed methodology decision before implementation.
