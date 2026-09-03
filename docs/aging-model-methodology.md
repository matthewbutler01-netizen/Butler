# Governed aging-model methodology

This document defines the rules Butler must follow before fitting or applying an empirical player-aging model. It is a methodology contract, not a fitted model.

## Objective

Estimate how observed NFL production rates change from one season to the next as players age, while keeping the result position-specific, metric-specific, provenance-aware, and inspectable.

The model is not a dynasty-value model, player grade, career-stage classifier, or team-strategy engine.

## Modeling population

The aging model must not be trained from only the players currently rostered in a Butler fantasy league. League sync persists league-relevant players, which would condition the training sample on current fantasy-roster survival and create avoidable survivor/selection bias.

The model-training universe must come from a broader historical NFL player population with stable player identity, exact birth date, and historical production evidence. League-specific outputs may later consume a validated model, but league membership must not define the training population.

The training-universe importer must preserve provider identity and provenance separately from Butler fantasy-league roster ownership. A later reconciliation layer may connect model-universe players to Butler players through exact provider IDs.

## Unit of observation

One candidate observation is a player with two consecutive regular seasons, `S` and `S+1`, where:

- the player has a canonical exact birth date;
- the selected production source has a stored snapshot for both seasons;
- both selected snapshots have `games_played > 0`;
- the player's normalized position is the same Butler position used for grouping;
- the same raw production metric is available in both seasons.

Repeated refreshes must not create repeated observations. Butler uses the latest stored snapshot for each player, season, and source.

## Age definition

Historical age must be derived only from a canonical exact birth date.

For reproducibility, Butler defines season age as age on **September 1 of the applicable NFL season year**. A pair from seasons `S` to `S+1` is attributed to the player's age on September 1 of season `S`.

Provider-reported current age must never be backdated into historical seasons or converted into an invented birth date.

## Outcome definition

The primitive outcome is the absolute year-over-year change in a raw per-game production rate:

```text
delta = rate(S+1) - rate(S)
```

Butler must preserve the starting rate, ending rate, and absolute delta for every observation.

The initial methodology does **not** use percentage change because percentage change is unstable when the starting value is zero or near zero.

No fantasy-point formula or cross-position composite score is part of the aging model.

## Position and metric separation

Observations must not be pooled across positions by default. Each position/metric combination is analyzed independently.

Candidate metrics are raw rates already supported by Butler:

- QB: passing yards/game, passing TD/game, interceptions/game, rushing yards/game, rushing TD/game.
- RB: rushing yards/game, rushing TD/game, receptions/game, receiving yards/game, receiving TD/game, fumbles lost/game.
- WR: receptions/game, receiving yards/game, receiving TD/game, rushing yards/game, rushing TD/game, fumbles lost/game.
- TE: receptions/game, receiving yards/game, receiving TD/game, fumbles lost/game.
- Other/unknown positions remain observable but must not be forced into QB/RB/WR/TE rules.

This list describes candidate model dimensions, not a weighting scheme.

## Availability and survivor bias

A player with a zero-game season has historical evidence but is not rate-eligible for that pair. A player with no following-season rate likewise produces no consecutive rate observation.

Butler must report these exclusions because a model built only from players who continue playing can contain survivor/availability bias. The initial model must not claim to estimate retirement, injury, roster survival, or availability risk.

Availability modeling is a separate future evidence/model problem.

## Sample audit before fitting

Before any curve is fitted, Butler must report for every position/metric/age cell:

- unique players;
- observation pairs;
- distinct season transitions represented;
- minimum and maximum transition season;
- starting-rate distribution summary;
- delta distribution summary;
- excluded zero-game and missing-follow-up counts when available.

The governed publication-support minimum is **5 distinct season transitions** per position/metric/age cell. This threshold was selected after the BF-191 through BF-205 calibration sequence inspected support tradeoffs, worst retained cells, age-band coverage, normalized instability, and canonical Pareto frontiers. The corresponding versioned policy identifier is `aging-support-v1-min-transitions-5`.

A cell below 5 distinct season transitions remains observable in diagnostics and sample audits but is not publication-eligible for downstream aging-model output. The threshold is global across QB/RB/WR/TE and is based on transition diversity, not raw pair count. It does not create an age limit, dynasty-value adjustment, or strategic player classification.

Any future threshold change requires a new empirical calibration record, a new policy identifier, and explicit methodology revision rather than silently changing the constant.

## First empirical summary

The first model-capable output should remain descriptive and robust:

- median year-over-year delta by position, metric, and integer season age;
- 25th and 75th percentile deltas;
- sample count and unique-player count;
- distinct season-transition count.

Mean and standard deviation may be reported as supplemental diagnostics but must not replace the robust summary by default.

A smoothed curve, regression, spline, LOESS fit, or hierarchical model requires a separate governed decision after the sample audit.

## Weighting

The initial descriptive summary gives each player-season pair one observation. Butler must not weight a pair by games played, fantasy value, roster slot, current dynasty value, or team ownership unless a later methodology explicitly introduces and justifies that weighting.

Player-level repeated observations across multiple ages remain visible as repeated longitudinal observations. Any later inferential model must account for within-player dependence rather than pretending all pairs are independent.

## Validation requirements

Before a fitted aging model may be used downstream, Butler must support at least:

1. reproducible extraction of the exact observation set;
2. sample-size and season-span audit by position/metric/age;
3. outlier and missingness inspection;
4. holdout or temporal validation of any fitted curve;
5. uncertainty reporting;
6. a versioned methodology/model identifier;
7. evidence-source and as-of provenance.

A model that cannot expose these inputs and diagnostics is not eligible to influence Butler recommendations.

## Governed descriptive age outlook

BF-225 introduces the first permitted interpretation layer after all 411 publication-eligible cells were shown to have complete validation envelopes.

The interpretation is intentionally **per metric** and descriptive. It does not aggregate metrics or modify dynasty value. The versioned policy identifier is `aging-outlook-v1-iqr-direction`.

For metrics where higher production is favorable (yards, touchdowns, receptions), Butler classifies the validated historical age-cell interval as:

- **FAVORABLE** only when the full interquartile delta interval is above zero;
- **UNFAVORABLE** only when the full interquartile delta interval is below zero;
- **NEUTRAL_OR_MIXED** when the interval includes or touches zero.

For turnover metrics where lower production is favorable (interceptions/game and fumbles lost/game), the direction is reversed before applying the same rule.

This rule deliberately uses the full 25th-to-75th percentile interval instead of only the median so a mixed historical distribution is not converted into a directional label. It introduces no minimum effect-size threshold and no cross-metric weighting.

A future player-level age outlook may consume these per-metric labels only after a separate governed aggregation rule is chosen and empirically inspected.

## Prohibited interpretations

This methodology still does not permit Butler to infer that a player is:

- "young" or "old" in a strategic sense;
- ascending, declining, washed, breaking out, or at peak age;
- a buy, sell, hold, contender piece, or rebuild piece;
- worth a specific dynasty-value adjustment because of age.

The BF-225 `FAVORABLE`, `NEUTRAL_OR_MIXED`, and `UNFAVORABLE` labels describe only the historical per-metric trajectory interval at a governed position/age cell. They are not player grades or recommendations.

Those downstream interpretations require separate governed rules plus validation.

## Next implementation steps

With publication support and validation complete, the next implementation work should:

1. apply `AgingModelAgeOutlookPolicy` only to validation-complete, publication-eligible metric cells;
2. expose the outlook policy identifier, metric direction, interval, and validation provenance together;
3. inspect the empirical distribution of favorable, neutral/mixed, and unfavorable labels across position/age cells before defining any player-level aggregation;
4. keep dynasty-value adjustments and recommendations prohibited until separately governed.
