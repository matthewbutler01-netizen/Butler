# Candidate threshold study implementation closeout

BF-525 through BF-528 implement the first governed, leakage-safe **candidate-threshold study** for Butler's lineup-capture rank-sensitivity evidence.

This layer studies observed candidate rules. It does **not** select or publish a production threshold.

## Implemented surfaces

- BF-525 normative methodology: [`league-lineup-capture-ranking-sensitivity-candidate-threshold-study-methodology.md`](league-lineup-capture-ranking-sensitivity-candidate-threshold-study-methodology.md)
- BF-526 analyzer/report: `LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer`
- BF-527 CLI:

```text
butler league lineup-capture-ranking-sensitivity-candidate-threshold-study <start-season> <end-season>
```

- BF-528 global help and durable closeout

## Implemented policy

```text
league-lineup-capture-ranking-sensitivity-candidate-threshold-study-v1-leave-one-league-season-out-observed-single-axis-breakpoints-descriptive-no-selection-no-confidence
```

The implementation consumes BF-522 structural readiness and therefore ultimately derives from governed BF-518 historical temporal-holdout evidence.

If BF-522 is not `READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN`, the candidate study fails closed and publishes no candidate folds.

## Leakage boundary

The evaluation cluster is `league-id + season`.

Every available league-season is held out exactly once. Candidate values for that fold are generated only from baseline sensitivity features in the other development clusters.

Held-out rows do not expand the candidate vocabulary for their own fold.

This is enforced visibly through:

- `EVALUABLE`
- `UNEVALUABLE_NO_HELD_OUT_SPLIT`
- `NOT_GENERATED_IN_DEVELOPMENT_FOLD`

A candidate absent from a development corpus remains `NOT_GENERATED_IN_DEVELOPMENT_FOLD` even when that value exists in the held-out cluster.

## Frequency candidate identity

BF-526 generates frequency candidates from observed BF-512 numerator/denominator pairs only.

Candidate identities are reduced canonical rationals. For example:

```text
2/10 -> 1/5
```

Comparison uses exact integer cross-multiplication rather than floating-point threshold arithmetic.

No unobserved decimal midpoint or hand-authored value is inserted.

The CLI may show the governed six-decimal display beside the exact rational identity, but the rational identity remains explicit.

## Maximum-movement candidate identity

The separate magnitude family uses observed BF-504 maximum-absolute-rank-movement integers only.

The v1 rule direction is fixed:

```text
maximumAbsoluteRankMovement <= candidateCutoff
```

The implementation never reverses the rule direction in a fold to improve apparent results.

## Candidate families remain separate

V1 does not implement:

- a magnitude x frequency matrix;
- a weighted magnitude-frequency score;
- learned coefficients;
- interaction terms;
- coverage-adjusted rules;
- baseline-rank-dependent rules; or
- team-count-normalized sensitivity scores.

The raw BF-512 frequency family and BF-504 maximum-movement family remain separate study surfaces.

## Held-out descriptive evidence

For each generated candidate and held-out cluster, BF-526 preserves both rule sides and exposes raw descriptive evidence including:

- team-cutoff row counts;
- exact numeric rank retained/moved counts;
- absolute temporal rank-displacement distribution;
- signed temporal rank-displacement distribution;
- BF-508 class distribution;
- changed-scenario numerator distribution;
- perturbation-denominator distribution;
- repository team-count context; and
- contributing cutoff weeks.

Team-cutoff rows remain correlated within league-seasons. Their counts are never an independent sample size.

## No forced held-out split

If all held-out rows fall on one side of a candidate, the candidate/fold state is:

```text
UNEVALUABLE_NO_HELD_OUT_SPLIT
```

Butler does not move or refine that threshold to force an evaluable split.

## Cross-fold support

BF-526 publishes deterministic cross-fold candidate support in candidate-value order.

It does not order candidates by retained counts, moved counts, displacement, or any apparent performance measure.

There is no `bestCandidate`, `winningCandidate`, `recommendedThreshold`, or equivalent field.

## What remains unauthorized

BF-525 through BF-528 do not authorize:

- choosing one candidate over another;
- defining an optimization objective;
- production threshold publication;
- qualitative calibrated sensitivity bands;
- threshold interpolation or refinement;
- random row-level model fitting;
- accuracy/F1/AUC/balanced-accuracy optimization;
- probability of future rank retention/movement;
- rank confidence;
- confidence intervals or significance tests;
- a confidence-adjusted or sensitivity-adjusted BF-500 rank;
- manager consistency/reliability/skill/quality grades;
- sensitivity leaderboards;
- recommendations; or
- cross-league manager comparisons.

## UX/product guardrail remains separate

Butler's product UX guardrail is tracked separately in GitHub issue #534. Candidate-study evidence does not authorize cluttering future interfaces with every diagnostic by default. Progressive disclosure, desktop-first quality, peak-load reliability, and no gambling-style engagement pressure remain product acceptance constraints independent of this analytical methodology.

## Next governed fork

After BF-528, the next decision is whether Butler should define a **candidate-selection objective methodology**.

The recommended next step is design-only: determine whether any objective can defensibly choose among candidate rules without converting the future-only ordinal holdout into ground truth, overfitting the small clustered corpus, or creating false confidence semantics.

No candidate-selection implementation should precede that methodology.
