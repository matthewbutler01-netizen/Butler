# Aging-model sample breadth

Use the breadth audit after the model universe has been populated and the model-free sample audit has been inspected.

```text
butler aging-model sample-audit
butler aging-model sample-breadth
```

`sample-breadth` describes the geometry and sparsity of the position/metric/age sample. It does not declare any sample cell sufficient or insufficient and does not choose a smoothing method.

## What is a dimension?

A dimension is one supported position/metric combination, such as:

```text
RB + RUSHING_YARDS_PER_GAME
WR + RECEIVING_YARDS_PER_GAME
QB + PASSING_TOUCHDOWNS_PER_GAME
```

Within a dimension, BF-167's sample cells are indexed by integer season age.

## Reported breadth diagnostics

For each dimension Butler reports:

- minimum and maximum observed age;
- number of observed age cells;
- full integer age span between the minimum and maximum;
- age-cell coverage percentage;
- explicit missing ages inside the observed span;
- total metric observations;
- minimum, median, and maximum observations per age cell;
- count of cells containing one observation;
- count of cells containing one unique player;
- count of cells represented by only one season transition;
- maximum distinct season-transition count represented by any cell.

The report also summarizes how many dimensions contain age gaps, single-observation cells, or single-transition cells.

## Why this exists before smoothing

A smoothed curve can visually hide thin evidence. A position/metric dimension may look continuous while actually being supported by isolated ages, one-player cells, or one historical transition.

Butler therefore exposes sample geometry before any smoothing or regression choice is permitted to influence downstream analysis.

The breadth audit is designed to answer questions such as:

- Is the observed age range continuous or fragmented?
- Are some ages represented by only one observation?
- Does the sample span multiple NFL seasons, or does a cell depend on one transition year?
- Is apparent age coverage broad enough to justify considering a continuous curve?

It does **not** answer whether the evidence is statistically sufficient. No minimum observation count, age coverage percentage, transition count, or player count is currently encoded as a pass/fail threshold.

## Interpretation boundary

`aging-model sample-breadth` does not produce:

- a readiness grade;
- a minimum sample rule;
- a smoothing window;
- a fitted curve;
- a peak-age estimate;
- a player age adjustment;
- a player or team strategy recommendation.

Any future publication threshold or smoothing method must be selected after inspecting the actual broad model-universe distribution and must be governed in a separate methodology change.
