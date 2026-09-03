# Governed local aging smoother

This document governs Butler's first smoothed aging-model output. It is deliberately narrower than a fitted career curve.

## Evidence basis

The empirical calibration run over nflverse regular-season data from 1999 through 2025 produced:

- 25,065 model-universe player profiles;
- 25,025 exact birth dates;
- 49,489 player-seasons;
- 36,181 consecutive season pairs;
- 10,559 exact-DOB, rate-eligible pairs;
- 57,306 position/metric observations;
- 438 position/metric/age sample cells;
- 21 position/metric dimensions;
- zero age gaps inside every observed dimension.

Observed cell density is strong through the central age ranges but falls sharply at the oldest and youngest edges. Every dimension contains at least one single-observation cell. This makes raw age-by-age medians useful as evidence but too noisy at the extremes to treat as a fitted career curve.

## First smoother

For a requested position, metric, and integer age `A`, Butler calculates a **centered local pooled summary** from raw eligible observations whose starting ages are in:

```text
A - 1, A, A + 1
```

The local summary is computed from the underlying player-season-pair observations, not from an average or median of already-aggregated age cells.

For the pooled window Butler reports:

- median year-over-year rate delta;
- 25th and 75th percentile delta;
- target-age observation count;
- total pooled observation count;
- unique-player count;
- distinct season-transition count;
- which integer ages actually contributed observations.

The raw center-age sample remains represented by `target-n` alongside the pooled output.

## CLI

The implemented descriptive surface is:

```text
butler aging-model local-smoother
```

The command accepts no additional arguments. It reads the broad aging-model universe from `butler.db` and prints every observed position/metric/age cell with its governed local summary.

Example output shape:

```text
RB RUSHING_YARDS_PER_GAME age=25 ages=[24, 25, 26] target-n=... pooled-n=... players=... transitions=... delta[p25=... median=... p75=...]
```

The header also reports total smoothed cells and the number of edge cells with fewer than three contributing ages.

The lack of CLI knobs is intentional: callers cannot silently change the window, add a support cutoff, combine positions, or turn the descriptive output into a player adjustment.

## Why a three-age window

The empirical sample is fully age-contiguous, so local pooling does not need to bridge missing internal ages. A one-year window on either side:

- increases support at sparse edge ages;
- remains local enough to avoid assuming a global career-curve shape;
- does not pool different positions or metrics;
- does not require a regression family, spline degrees, bandwidth optimizer, or hand-built peak-age assumption;
- remains directly reconstructable from the governed observation set.

This is a descriptive smoother, not an inferential model.

## Boundary ages

At the minimum or maximum observed age for a dimension, Butler uses only ages that actually exist inside the `A ± 1` window. It must not extrapolate outside the observed age range.

A two-age boundary window is therefore valid and must disclose the contributing ages.

## Support and publication

This methodology version defines **no hard publication threshold** for observation count, unique players, or season transitions.

Sparse outputs are not hidden. Butler exposes support counts so downstream consumers can distinguish a well-supported local estimate from an edge estimate built from few observations.

A future publication/readiness rule may suppress or qualify low-support estimates only after validation against this empirical distribution and out-of-sample behavior.

## Weighting

Every eligible player-season pair contributes one observation. Butler does not weight by:

- games played;
- fantasy value;
- current roster status;
- dynasty value;
- player name recognition;
- team ownership;
- distance from the center age.

Ages `A-1`, `A`, and `A+1` are pooled equally at the observation level. Larger age cells contribute more because they contain more actual observations, not because Butler assigns them an external weight.

## Position and metric isolation

The local smoother preserves the same position/metric boundaries as the sample audit. No cross-position or cross-metric pooling is permitted.

It does not create a fantasy-point composite or universal aging coefficient.

## Current implementation boundary

BF-175 implements the governed local-window analyzer. BF-176 exposes it through the consolidated CLI router.

Neither layer:

- changes player values;
- labels career stage;
- predicts an individual player's next season;
- declares a sample sufficient or insufficient;
- applies a publication threshold;
- generates trade or roster recommendations.

## Interpretation boundary

The local pooled median is an empirical description of historical year-over-year change for one position/metric/age neighborhood. It is not permission to declare that an individual player is:

- ascending or declining;
- pre-peak, peak, or post-peak;
- young or old in a strategic sense;
- a buy, sell, or hold;
- worth a specific dynasty-value adjustment.

Individual-player age adjustment requires separate validation and governance.

## Validation sequence

Before this smoother may influence a downstream player adjustment, Butler must add and inspect:

1. deterministic local-window extraction tests — implemented in BF-175;
2. temporal holdout diagnostics;
3. sensitivity comparison against the unsmoothed center-age median;
4. stability diagnostics when one season transition is removed;
5. explicit support/provenance rendering — implemented in BF-175/BF-176 for descriptive output.

The current implementation remains descriptive only. It does not alter player values or recommendations.
