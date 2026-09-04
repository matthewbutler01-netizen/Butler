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

## Single-asset candidate discovery

The first governed asset-discovery policy is `trade-counter-single-asset-candidate-v1-market-fair-minimum-excess`.

It remains market-only and non-prescriptive. It discovers real single-player or single-draft-pick adjustments that satisfy the existing counter-value target, but it returns a ranked candidate set rather than selecting a winner.

Candidate discovery requires:

- available counter-value context from complete, fresh trade evidence;
- each trade package to resolve to exactly one current fantasy-team owner;
- the two package owners to be distinct;
- the current league inventory to use the same league and value source as the trade evidence;
- an inventory asset to meet the trade's explicit minimum-as-of boundary when one is present.

For the add-to-lower path, Butler searches only the lower-valued package owner's current inventory. Assets already present anywhere in the proposed trade are excluded. For the remove-from-higher path, Butler considers only assets already present in the higher-valued package; unrelated assets on that owner's roster are not removal candidates.

Every modified package is run back through `trade-fairness-measure-v1-midpoint-percent` and `trade-fairness-v1-midpoint-gap-5pct`. A candidate is retained only when the resulting trade is actually `MARKET_FAIR`.

Candidates are sorted deterministically by the smallest asset-value excess above the governed minimum required change, then by asset value, adjustment type, asset type, and stable asset ID. This ranking is evidence ordering only; it is not an instruction to choose the first candidate.

If the current trade is already market-fair, candidate discovery is available and returns an empty candidate set. If governed market evidence is incomplete or stale, or package ownership is ambiguous, candidate discovery fails closed without candidates.

## Read-only CLI surface

The governed evidence can be inspected without changing the live recommendation contract:

`butler trade counter-value <league-id> <side-a-assets> <side-b-assets> [source] [--minimum-as-of YYYY-MM-DD]`

Asset-package syntax matches the existing mixed trade comparison surface: comma-separated bare IDs are players, while explicit `player:<id>` and `pick:<draft-pick-id>` tokens may be mixed in either package.

The command prints:

- market-value coverage and stale-asset count;
- side A and side B package totals;
- counter-context and target policy identifiers;
- current governed fairness and symmetric gap when evidence is available;
- the add-to-lower and remove-from-higher value targets when the trade is outside the band;
- an explicit insufficiency reason when package values are incomplete or stale.

The command is routed independently as `trade counter-value`; it does not route through `trade recommendation`, does not select an asset, and does not emit a team action or package recommendation.

BF-369 candidate discovery is not yet printed by this CLI. Exposing candidates is a separate compatibility surface so the market-only target command remains stable while candidate semantics are reviewed independently.

## Boundary calculation

For higher package value `H`, lower package value `L`, and the governed fair-gap percentage `p`, the real-number boundary targets are:

- add-to-lower target: `H * (200 - p) / (200 + p)`;
- remove-from-higher target: `L * (200 + p) / (200 - p)`.

For v1, `p` is not copied into this policy as a new constant. The analyzer reads `TradeFairnessPolicy.MAXIMUM_FAIR_GAP_PERCENT`, so the counter target remains explicitly derived from the existing governed fairness policy.

Because an exact real-number 5% boundary can be represented by binary floating point as a value microscopically above 5%, Butler verifies every calculated target by running it back through the existing fairness measurement and classification. If necessary, it moves the target one representable floating-point step toward fairness with `Math.nextUp` for add-to-lower or `Math.nextDown` for remove-from-higher. A target is returned only if the existing fairness policy then classifies it `MARKET_FAIR`.

## Interpretation boundary

These policies and the CLI are counter evidence only. They do **not**:

- automatically select a player or draft pick to add or remove;
- choose between add-to-lower and remove-from-higher strategies;
- infer a team perspective;
- emit `COUNTER`, `ACCEPT`, `REJECT`, `HOLD`, or `INCONCLUSIVE`;
- modify Trade Recommendation v5;
- use posture, age, future capital, positional pressure, flexible pressure, or strategic veto evidence;
- alter persisted market values or the existing 5% fairness threshold.

Future team-perspective counter construction, strategic candidate filtering, multi-asset package construction, and any new `COUNTER` action require separately versioned policies and compatibility contracts.
