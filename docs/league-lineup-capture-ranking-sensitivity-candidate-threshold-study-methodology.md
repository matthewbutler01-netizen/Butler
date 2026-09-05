# Governed lineup-capture rank-sensitivity candidate-threshold study methodology

BF-525 defines how Butler may **study candidate sensitivity thresholds descriptively** after the BF-521/BF-522 corpus-readiness layer reports `READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN`.

This methodology authorizes candidate generation and out-of-cluster descriptive evaluation only. It does **not** authorize selecting, fitting, publishing, or deploying a production threshold; estimating confidence/probability; adjusting BF-500 ranks; or scoring managers.

## Methodology status

The v1 decision is:

```text
CANDIDATE_THRESHOLD_STUDY_AUTHORIZED
PRODUCTION_THRESHOLD_SELECTION_NOT_AUTHORIZED
STATISTICAL_ADEQUACY_NOT_ESTABLISHED
CONFIDENCE_OR_PROBABILITY_NOT_AUTHORIZED
```

## Source of truth

The only authorized source is a valid BF-522:

`LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.CorpusReadinessReport`

The source must be:

```text
READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN
```

If readiness is not satisfied, the entire BF-525 study is unavailable. Butler must not weaken BF-521 gates, synthesize historical rows, import unsupported leagues, or silently narrow the requested historical scope to manufacture readiness.

The nested BF-518 corpus audit remains the authoritative historical row source.

## Study question

BF-525 asks only:

> Do simple, predeclared candidate rules based on Butler's already-governed sensitivity diagnostics show materially different **descriptive future-only ordinal outcomes** when evaluated outside the league-season clusters used to derive the candidates?

The later holdout rank is still not truth, corrected rank, manager quality, or proof that the baseline rank was right or wrong.

## Independent evaluation unit

The primary cluster is:

```text
league-id + season
```

Team-cutoff rows, cutoffs from the same league-season, and teams from the same cutoff remain correlated.

BF-525 prohibits random row-level train/test splitting.

## Leakage-safe evaluation

V1 uses deterministic **leave-one-league-season-out** evaluation.

For each available league-season cluster `H`:

1. `H` is the held-out evaluation cluster.
2. Every other available league-season cluster is the development corpus for that fold.
3. Candidate values are generated only from development-cluster source values.
4. The candidate is applied to the held-out cluster without modification.
5. No held-out future outcome may influence candidate generation, rule direction, tie handling, or candidate ordering.

Every available league-season cluster must serve as the holdout exactly once.

If fewer than two available league-season clusters remain after source validation, the study is unavailable.

## V1 candidate families

V1 intentionally keeps candidate families separate.

### Family A — raw rank-change frequency

Candidate thresholds may use only BF-512:

```text
baselineRankChangedScenarios / perturbationDenominator
```

Candidate frequency values must be generated from **observed development-corpus rational breakpoints**, preserving numerator and denominator provenance.

Butler may compare a row using exact cross-multiplication rather than floating-point approximation:

```text
rowChanged * candidateDenominator
    <=
candidateNumerator * rowDenominator
```

for an inclusive candidate rule.

Equal six-decimal displays produced by different rational pairs must remain distinguishable in diagnostics.

No hand-authored decimal such as `0.20`, `0.25`, or `0.50` may enter v1 unless that exact rational breakpoint is present in the development corpus.

### Family B — maximum absolute rank movement

Candidate magnitude rules may use only BF-504 maximum absolute rank movement.

Candidate integer cutoffs must come from observed development-corpus values.

For v1 the direction is fixed and predeclared:

```text
maximumAbsoluteRankMovement <= candidateCutoff
```

Lower observed movement is the candidate "lower-sensitivity" side. The study does not reverse direction per fold to improve results.

## No combined candidate model in v1

BF-525 does **not** authorize:

- magnitude x frequency matrices;
- weighted magnitude-frequency scores;
- interaction terms;
- coverage-adjusted rules;
- baseline-rank-dependent rules;
- team-count-normalized movement scores;
- learned coefficients; or
- multi-feature optimization.

The first study must establish whether either single governed axis carries useful out-of-cluster descriptive separation before Butler considers a combined methodology.

## Candidate generation

Within each fold, a candidate set is derived only from development clusters.

For frequency candidates:

- collect distinct observed `(changedScenarios, perturbationDenominator)` pairs;
- reduce pairs to canonical rational form for candidate identity;
- preserve all original denominator-support diagnostics;
- sort candidates by exact rational value;
- do not invent midpoint thresholds.

For magnitude candidates:

- collect distinct observed maximum-absolute-rank-movement integers;
- sort ascending;
- do not invent unobserved intermediate values solely to increase the grid.

Candidate generation is deterministic.

## Descriptive held-out outcomes

Each candidate divides held-out team-cutoff rows into two descriptive sides:

```text
MEETS_CANDIDATE_RULE
DOES_NOT_MEET_CANDIDATE_RULE
```

For each side, Butler may report only raw descriptive evidence including:

- held-out league-season identity;
- held-out cutoff count;
- held-out team-cutoff row count;
- exact-rank-retained row count;
- moved row count;
- absolute temporal rank-displacement distribution;
- signed temporal rank-displacement distribution;
- baseline sensitivity-class distribution;
- changed-scenario numerator distribution;
- perturbation-denominator distribution; and
- repository team-count context.

Rows from the same cluster are not independent observations.

## Fold-level candidate support

A candidate is **evaluable in a fold** only when the held-out cluster contains at least one row on both sides of the rule.

If every held-out row falls on the same side, that fold/candidate is `UNEVALUABLE_NO_HELD_OUT_SPLIT` and remains visible.

Butler must not move the threshold to force a split.

## Cross-fold descriptive summary

For each candidate identity, Butler may aggregate only cluster-aware descriptive counts:

- folds where the candidate existed in the development corpus;
- evaluable folds;
- unevaluable folds;
- held-out league-season identities evaluated;
- per-fold retained/moved counts on each rule side; and
- per-fold displacement summaries.

BF-525 does not authorize pooling all held-out team-cutoff rows into one independent-N confusion matrix.

## No candidate winner

V1 must not declare:

- best threshold;
- optimal threshold;
- recommended threshold;
- production threshold;
- winning candidate;
- statistically significant candidate;
- calibrated sensitivity band; or
- threshold confidence.

Candidate ordering in output must be deterministic by family and candidate value, never by apparent performance.

## No optimization objective

BF-525 explicitly does not choose an objective such as:

- maximize future rank retention;
- maximize accuracy;
- maximize balanced accuracy;
- maximize F1;
- minimize displacement;
- maximize AUC;
- maximize information gain; or
- minimize any fitted loss.

Those choices are policy/model-selection decisions and require a later methodology.

## Denominator and league-size diagnostics

Every frequency candidate summary must retain denominator support by fold.

Every candidate summary must retain repository team-count support by fold.

A rule that appears only in one denominator or one league-size stratum remains visibly narrow. BF-525 does not extrapolate beyond observed support.

## Missing candidate support

If a candidate is generated in some development folds but not others, Butler must report exactly which folds generated it.

It must not create a global candidate vocabulary from the full corpus before leave-one-cluster-out evaluation because that would leak held-out feature support into development.

## No threshold fitting

Candidate values are observed source breakpoints, not fitted parameters.

BF-525 does not authorize interpolation, gradient search, grid refinement around promising values, Bayesian optimization, recursive partitioning, decision trees, logistic regression, isotonic calibration, Platt scaling, or any other fitting process.

## No probability or confidence semantics

The study may not convert held-out descriptive frequencies into:

- probability of future rank retention;
- probability of future rank movement;
- confidence in BF-500 rank;
- confidence intervals;
- p-values;
- standard errors;
- bootstrap confidence;
- posterior probability; or
- reliability scores.

## No manager attribution

No candidate result may be described as manager consistency, manager reliability, manager skill, coaching quality, decision quality, fault, or causal responsibility.

The study concerns only persistence behavior of Butler's governed lineup-capture ordinal artifact.

## Required study states

A v1 implementation should expose report-level states equivalent to:

```text
AVAILABLE
UNAVAILABLE_CORPUS_NOT_STRUCTURALLY_READY
UNAVAILABLE_INSUFFICIENT_EVALUATION_CLUSTERS
```

Candidate-fold states should include:

```text
EVALUABLE
UNEVALUABLE_NO_HELD_OUT_SPLIT
NOT_GENERATED_IN_DEVELOPMENT_FOLD
```

No partial report should be fabricated when the source readiness prerequisite fails.

## Policy identifier

A conforming BF-525 implementation should use:

```text
league-lineup-capture-ranking-sensitivity-candidate-threshold-study-v1-leave-one-league-season-out-observed-single-axis-breakpoints-descriptive-no-selection-no-confidence
```

The metric scope should state that the artifact is a leakage-safe, cluster-aware descriptive study of observed single-axis sensitivity candidate rules against future-only ordinal persistence evidence, with no production threshold selection, statistical sufficiency claim, probability, confidence, rank adjustment, or manager attribution.

## Authorized next sequence

BF-525 authorizes only:

1. **BF-526** — candidate-threshold study analyzer/report derived from BF-522/BF-518;
2. **BF-527** — CLI exposing folds, candidates, support, and descriptive outcomes;
3. **BF-528** — global help and durable documentation closeout; and
4. stop before any candidate-selection objective, production threshold, calibrated category, confidence/probability model, adjusted rank, manager score, sensitivity leaderboard, or recommendation.

**Stop boundary:** after BF-528, a new governed methodology decision is required before Butler may define an optimization/selection objective, choose one candidate over another, fit/refine thresholds, publish calibrated categories, estimate confidence/probability, adjust BF-500 ranks, evaluate managers, rank teams by sensitivity, issue recommendations, or make cross-league manager comparisons.
