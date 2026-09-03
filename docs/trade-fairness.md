# Governed Trade Fairness

Butler's first trade-fairness interpretation is deliberately narrow. It answers whether two complete player-trade packages are close in persisted market value under the governed v1 tolerance and, when they are outside that band, which side carries the higher persisted market value. It does not declare a winner and it does not recommend accepting or rejecting a trade.

## Market-value measurement

The measurement policy is `trade-fairness-measure-v1-midpoint-percent`.

For complete market-value coverage, Butler calculates the symmetric percentage gap:

`abs(sideAValue - sideBValue) / ((sideAValue + sideBValue) / 2) * 100`

Example: values of 105 and 95 have a midpoint of 100 and an absolute gap of 10, so the symmetric gap is 10%.

If both totals are zero, the symmetric gap is 0%. If either trade package has incomplete market-value coverage, the fairness measurement is unavailable. Butler does not substitute a partial total or estimate the missing value.

## Fairness policy

The classification policy is `trade-fairness-v1-midpoint-gap-5pct`.

The governed v1 tolerance is exactly 5.000%:

- `MARKET_FAIR`: symmetric market-value gap is between 0% and 5.000%, inclusive.
- `OUTSIDE_FAIRNESS_BAND`: symmetric market-value gap is greater than 5.000%.
- `UNAVAILABLE`: market-value coverage is incomplete and therefore no valid gap is available.

The boundary is intentional: 5.000% is `MARKET_FAIR`; 5.001% is `OUTSIDE_FAIRNESS_BAND`.

## Market-edge direction

The directional policy is `trade-market-edge-v1-outside-fairness-band`.

Market-edge direction is derived only after the fairness classification is known:

- `MARKET_FAIR`: no directional edge is declared inside the 5% fairness band.
- `SIDE_A_MARKET_EDGE`: the trade is outside the fairness band and Side A has the higher persisted market-value total.
- `SIDE_B_MARKET_EDGE`: the trade is outside the fairness band and Side B has the higher persisted market-value total.
- `UNAVAILABLE`: market-value coverage is incomplete.

The signed A-B market-value difference determines direction outside the band. Positive means Side A has the market-value edge; negative means Side B has the market-value edge.

A market-value edge is not a winner label. It does not say the higher-valued side should accept the trade, that the lower-valued side should reject it, or that the higher-valued package is strategically better for a specific roster.

## Supporting evidence is independent

Age-outlook supporting flags do not modify either side's persisted market value, the symmetric gap, the fairness classification, or the market-edge direction. A favorable or unfavorable age flag cannot pull an outside-band trade into `MARKET_FAIR`, push a market-fair trade outside the band, or flip which side carries the market-value edge.

Supporting evidence remains descriptive context beside the market-value result. Empty supporting evidence is valid and does not make an otherwise complete market-value comparison unavailable.

## CLI surface

The player-only command remains:

`butler trade supporting-evidence <league-id> <season> <side-a-player-ids> <side-b-player-ids> [source]`

The output includes:

- persisted market-value source and coverage,
- side totals and signed A-B value difference,
- fairness measurement policy ID,
- fairness policy ID,
- symmetric market-value gap,
- `MARKET_FAIR`, `OUTSIDE_FAIRNESS_BAND`, or `UNAVAILABLE`,
- market-edge policy ID,
- `MARKET_FAIR`, `SIDE_A_MARKET_EDGE`, `SIDE_B_MARKET_EDGE`, or `UNAVAILABLE`,
- governed supporting-evidence flags and their provenance.

Draft picks are not accepted by this supporting-evidence surface because the current supporting flags are player aging evidence.

## Interpretation boundary

`MARKET_FAIR` means only that the two complete packages fall within the governed 5% market-value band. `SIDE_A_MARKET_EDGE` and `SIDE_B_MARKET_EDGE` mean only that the named side has the higher persisted market value when the trade is outside that band.

These policies do not provide:

- a winning side,
- accept/reject guidance,
- contender/rebuilder interpretation,
- roster-need weighting,
- age discounts or premiums,
- production weighting,
- confidence weighting,
- buy/hold/sell labels,
- any hidden adjustment to persisted market values.

Any of those behaviors requires a separately governed policy before it can enter Butler's decision layer.
