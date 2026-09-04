# Governed Trade Counter Value Targets

Butler's first counter-trade primitive is deliberately narrower than a counter recommendation. It answers one market-only question: when two complete trade packages are outside the existing governed 5% fairness band, how much package value would need to change for the trade to enter that band?

Policy: `trade-counter-value-target-v1-market-fairness-boundary`

This policy reuses, without changing:

- fairness measurement: `trade-fairness-measure-v1-midpoint-percent`;
- fairness classification: `trade-fairness-v1-midpoint-gap-5pct`;
- the existing inclusive 5.000% market-fair boundary.

## Inputs and availability

The numeric target analyzer accepts two complete, finite, non-negative package-value totals. It does not estimate missing market values or consume partial package totals.

The trade-evidence composition policy is `trade-counter-value-context-v1-comparable-market-evidence`. It accepts an existing `TradeAssetAnalyzer.TradeReport` and exposes a counter target only when the report is both complete and fresh under that report's governed freshness boundary.

- missing market values make the counter context unavailable;
- explicitly stale market values make the counter context unavailable;
- no partial total or stale total is passed into the counter-target calculation;
- a complete, fresh trade that is already market-fair remains available evidence but has no adjustment options.

The context preserves the trade's league ID, value source, and minimum-as-of boundary together with the counter-target policy provenance.

If the current trade is already `MARKET_FAIR`, no counter adjustment is needed and the result contains no adjustment options.

If the trade is `OUTSIDE_FAIRNESS_BAND`, Butler returns two deterministic, asset-neutral adjustment targets:

1. add value to the lower-valued package until the trade reaches the governed fairness band;
2. remove value from the higher-valued package until the trade reaches the governed fairness band.

The analyzer does not prefer one adjustment strategy over the other.

## Boundary calculation

For higher package value `H`, lower package value `L`, and the governed fair-gap percentage `p`, the real-number boundary targets are:

- add-to-lower target: `H * (200 - p) / (200 + p)`;
- remove-from-higher target: `L * (200 + p) / (200 - p)`.

For v1, `p` is not copied into this policy as a new constant. The analyzer reads `TradeFairnessPolicy.MAXIMUM_FAIR_GAP_PERCENT`, so the counter target remains explicitly derived from the existing governed fairness policy.

Because an exact real-number 5% boundary can be represented by binary floating point as a value microscopically above 5%, Butler verifies every calculated target by running it back through the existing fairness measurement and classification. If necessary, it moves the target one representable floating-point step toward fairness with `Math.nextUp` for add-to-lower or `Math.nextDown` for remove-from-higher. A target is returned only if the existing fairness policy then classifies it `MARKET_FAIR`.

## Interpretation boundary

These policies are counter evidence only. They do **not**:

- select a player or draft pick to add or remove;
- search a roster for matching assets;
- choose between the two adjustment strategies;
- infer a team perspective;
- emit `COUNTER`, `ACCEPT`, `REJECT`, `HOLD`, or `INCONCLUSIVE`;
- modify Trade Recommendation v5;
- use posture, age, future capital, positional pressure, flexible pressure, or strategic veto evidence;
- alter persisted market values or the existing 5% fairness threshold.

Future asset selection, team-perspective counter construction, and any new `COUNTER` action require separately versioned policies and compatibility contracts.
