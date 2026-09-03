# Aging-model sample audit

Butler's aging-model workflow separates evidence collection, sample auditing, and model fitting. The sample audit is the last model-free checkpoint before any smoothing or fitted curve is considered.

## Command

```text
butler aging-model sample-audit
```

The command operates on Butler's broad nflverse-backed modeling universe, not on a fantasy league or current roster. It intentionally accepts no league id, season, sample threshold, smoothing parameter, or model option.

Before running it, populate the modeling universe:

```text
butler nflverse aging-model-players-preview
butler nflverse aging-model-players-refresh
butler nflverse aging-model-production-preview <start-season> <end-season>
butler nflverse aging-model-production-refresh <start-season> <end-season>
butler aging-model sample-audit
```

## Eligible year-over-year pair

A candidate pair uses consecutive regular seasons `S` and `S+1`. It becomes rate-eligible only when:

- both season snapshots have `games_played > 0`;
- the model player profile contains an exact birth date;
- the season-specific position is unchanged across the pair;
- the position is one of Butler's currently supported model positions: QB, RB, WR, or TE.

Age is derived from the exact birth date on September 1 of season `S`. Provider-reported current age is not backdated.

## Raw outcomes

For each supported position/metric combination Butler preserves:

- start-season raw per-game rate;
- end-season raw per-game rate;
- absolute year-over-year delta: `end rate - start rate`.

Percentage change is not used because it becomes unstable around zero-valued starting rates.

No fantasy-point formula or cross-position score is introduced.

## Grouped sample cells

Observations are grouped by:

```text
position + metric + integer season age
```

Each sample cell reports:

- observation count;
- unique-player count;
- distinct season-transition count;
- minimum and maximum starting season;
- median starting rate;
- 25th-percentile delta;
- median delta;
- 75th-percentile delta.

These summaries are descriptive. A cell is not declared statistically sufficient merely because it exists.

## Exclusions remain visible

The audit reports counts for:

- zero-game consecutive pairs;
- consecutive pairs without an exact birth date;
- position-change pairs;
- unsupported-position pairs;
- production players with no model profile;
- model-profile players with no production history.

This is important because excluding unavailable or non-surviving players can create selection and survivor bias. The audit surfaces those boundaries rather than pretending the observed rate sample represents availability or retirement risk.

## Interpretation boundary

`aging-model sample-audit` does **not** produce:

- an aging curve;
- a peak-age estimate;
- an age-adjusted player score;
- a young/old label;
- a breakout/decline label;
- a dynasty-value adjustment;
- a buy/sell/hold recommendation.

No minimum sample threshold or smoothing method has been selected yet. Those choices must follow inspection of the actual position/metric/age distribution and must be governed separately.
