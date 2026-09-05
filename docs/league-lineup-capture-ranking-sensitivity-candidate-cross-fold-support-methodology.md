# Governed candidate cross-fold support methodology

BF-529 decides whether Butler may automatically select a candidate sensitivity threshold after BF-525/BF-526 candidate-study evidence exists.

The v1 decision is deliberately conservative:

```text
AUTOMATIC_CANDIDATE_SELECTION_NOT_AUTHORIZED_V1
CANDIDATE_CROSS_FOLD_SUPPORT_AUDIT_AUTHORIZED
PRODUCTION_THRESHOLD_NOT_AUTHORIZED
CONFIDENCE_OR_PROBABILITY_NOT_AUTHORIZED
```

BF-529 therefore authorizes only a read-only, cluster-aware **support and directional-consistency audit** over the completed BF-526 candidate study. It does not choose a winner.

## Why automatic selection remains unauthorized

BF-526 evaluates deterministic candidate rules against future-only ordinal movement of the same governed lineup-capture metric. That future rank is useful out-of-window evidence, but it is not ground truth.

Selecting the candidate with the largest apparent held-out separation would be unsafe because:

- league-season clusters are few and internally correlated;
- candidate availability differs by development fold;
- some candidate/fold pairs are unevaluable because the held-out cluster does not split;
- team-count and perturbation-denominator support can differ by candidate;
- the future-only rank is not a true/corrected target label; and
- maximizing apparent separation on the same small audit corpus would introduce a model-selection step that BF-525 intentionally prohibited.

BF-529 does not convert descriptive holdout evidence into a winner merely because the study can now display it.

## Source of truth

The only authorized source is a valid BF-526:

`LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.CandidateThresholdStudyReport`

The BF-526 report must be `AVAILABLE`.

If the source is unavailable, the entire BF-529 audit is unavailable. Butler must not synthesize candidates, reconstruct folds, pool excluded historical cutoffs, or weaken BF-521/BF-525 prerequisites.

## Audit question

BF-529 asks only:

> Which already-generated candidate identities have enough repeated **descriptive cross-fold support** to justify further methodology study, and what direction does their held-out ordinal displacement evidence take across evaluable league-season folds?

This question does not ask which candidate should be deployed.

## Candidate identity remains unchanged

BF-529 must use BF-526 candidate identities exactly as published.

Frequency family identities remain canonical exact rationals, such as `1/5`.

Magnitude family identities remain observed integer maximum-movement cutoffs, such as `<= 1`.

BF-529 must not:

- merge nearby candidate values;
- interpolate new candidates;
- create midpoint thresholds;
- refit candidate values;
- reverse candidate direction; or
- create a combined magnitude-frequency rule.

## Primary cluster remains league-season

The primary evidence cluster remains:

```text
league-id + season
```

Team-cutoff rows are correlated and must not be treated as independent samples.

Every audit count must preserve whether it is a fold/cluster count versus a row count.

## Required candidate support diagnostics

For each BF-526 candidate, BF-529 requires explicit counts for:

- total BF-526 folds;
- folds where the candidate was generated from development evidence;
- folds where the candidate was `NOT_GENERATED_IN_DEVELOPMENT_FOLD`;
- generated folds that were `EVALUABLE`;
- generated folds that were `UNEVALUABLE_NO_HELD_OUT_SPLIT`;
- distinct held-out league IDs among evaluable folds;
- distinct held-out seasons among evaluable folds;
- repository team-count strata represented in evaluable held-out folds; and
- for frequency candidates, perturbation denominators represented on both held-out rule sides.

These are support diagnostics, not statistical sample-size claims.

## V1 support states

BF-529 authorizes deterministic descriptive support states:

```text
NO_EVALUABLE_FOLDS
SINGLE_EVALUABLE_FOLD
MULTI_FOLD_NARROW_SUPPORT
MULTI_FOLD_DIVERSE_SUPPORT
```

These states describe evidence breadth only.

They are not low/medium/high confidence and do not authorize selection.

### NO_EVALUABLE_FOLDS

No held-out league-season fold is `EVALUABLE` for the candidate.

### SINGLE_EVALUABLE_FOLD

Exactly one held-out league-season fold is `EVALUABLE`.

### MULTI_FOLD_NARROW_SUPPORT

At least two held-out league-season folds are `EVALUABLE`, but evaluable support remains confined to only one observed repository team-count stratum, or—where applicable to the frequency family—only one perturbation-denominator context.

### MULTI_FOLD_DIVERSE_SUPPORT

At least two held-out league-season folds are `EVALUABLE` and evaluable support spans:

- at least two held-out league-season identities; and
- at least two repository team-count strata.

For a frequency candidate, evaluable side evidence must also expose at least two perturbation-denominator values across the candidate's evaluable held-out evidence before `MULTI_FOLD_DIVERSE_SUPPORT` is allowed.

This is a structural support label only. Two folds or two strata are minimum variation conditions, not proof of adequacy.

## Directional displacement evidence

For each evaluable fold/candidate, BF-529 may compare the two already-published BF-526 sides using **raw absolute temporal rank displacement**.

The permitted fold-level direction states are:

```text
MEETS_SIDE_LOWER_TOTAL_ABSOLUTE_DISPLACEMENT
MEETS_SIDE_HIGHER_TOTAL_ABSOLUTE_DISPLACEMENT
EQUAL_TOTAL_ABSOLUTE_DISPLACEMENT
```

The fold calculation is:

```text
sum absolute temporal rank displacement on MEETS_CANDIDATE_RULE side
versus
sum absolute temporal rank displacement on DOES_NOT_MEET_CANDIDATE_RULE side
```

However, raw totals are sensitive to different side row counts. Therefore BF-529 must also preserve each side's row count and full displacement distribution beside the direction state.

The direction state is descriptive only and must not be called an error rate, accuracy, loss, or probability.

## No normalized winner metric in v1

BF-529 does not authorize computing a scalar candidate score from:

- displacement per row;
- mean displacement;
- median displacement;
- retention percentage;
- accuracy;
- F1;
- balanced accuracy;
- AUC;
- weighted fold wins;
- league-size weights;
- denominator weights; or
- any composite of support breadth and displacement direction.

Those would require a separate objective methodology and assumptions about how row imbalance and ordinal distance should be normalized.

## Directional-consistency summary

Across evaluable folds, BF-529 may count how many folds are in each permitted direction state.

It may also expose the ordered list of fold direction states.

It must not convert those counts into:

- a consistency percentage interpreted as confidence;
- a win rate;
- a probability;
- a p-value;
- a support score; or
- a ranking of candidates.

## Candidate ordering

Candidates remain ordered exactly as BF-526 orders them:

1. family; then
2. candidate value.

No support state, direction count, displacement total, retained-row count, or other audit result may reorder candidates.

## No automatic candidate-selection rule

BF-529 explicitly rejects rules such as:

- choose every `MULTI_FOLD_DIVERSE_SUPPORT` candidate;
- choose the candidate with most lower-displacement folds;
- choose the candidate with fewest higher-displacement folds;
- choose the lowest supported frequency threshold;
- choose the most conservative candidate;
- choose the largest apparent separation; or
- break ties using team-count or denominator breadth.

The support audit tells Butler what evidence exists. It does not make the selection decision.

## No manager attribution

No support state or direction state may be interpreted as manager consistency, reliability, quality, skill, fault, or decision quality.

The subject remains persistence behavior of Butler's governed lineup-capture ordinal artifact.

## No probability/confidence semantics

BF-529 does not authorize:

- probability of future rank retention;
- probability of future rank movement;
- confidence in a baseline rank;
- confidence in a candidate threshold;
- confidence intervals;
- statistical significance;
- bootstrap confidence;
- Bayesian posterior probability; or
- reliability scores.

## No production threshold

Even a candidate with `MULTI_FOLD_DIVERSE_SUPPORT` and lower total absolute displacement on every evaluable fold remains **not selected** and **not production-authorized**.

This is intentional.

## Report availability

A conforming implementation should expose report-level states equivalent to:

```text
AVAILABLE
UNAVAILABLE_CANDIDATE_STUDY
```

If BF-526 is unavailable, no partial candidate support audit is published.

## Policy identifier

A conforming BF-529 implementation should use:

```text
league-lineup-capture-ranking-sensitivity-candidate-cross-fold-support-v1-cluster-aware-breadth-and-direction-audit-no-selection-no-score-no-confidence
```

The metric scope should state that the artifact audits structural candidate support and raw held-out displacement direction across governed league-season folds without selecting, scoring, optimizing, fitting, or deploying a threshold.

## Authorized next sequence

BF-529 authorizes only:

1. **BF-530** — cross-fold candidate support/consistency audit analyzer derived from BF-526;
2. **BF-531** — CLI exposing support states, fold direction states, and raw side diagnostics;
3. **BF-532** — global help and durable documentation closeout; and
4. stop again before any candidate selection objective, production threshold, calibrated category, confidence/probability model, adjusted rank, manager score, sensitivity leaderboard, or recommendation.

**Stop boundary:** after BF-532, a new governed methodology decision is required before Butler may define a scalar objective, normalize displacement into a candidate score, choose one threshold, break candidate ties by performance, fit/refine candidates, publish calibrated categories, estimate confidence/probability, adjust BF-500 ranks, evaluate managers, rank teams by sensitivity, or issue recommendations.
