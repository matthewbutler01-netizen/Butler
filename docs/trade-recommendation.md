# Trade Recommendation

Butler's trade recommendation surface is conservative, market-first, and evidence-gated. It converts governed trade evidence into a package recommendation and, only when an explicit team perspective is supplied, into a team action.

## Command

```text
butler trade recommendation <league-id> <season> <side-a-assets> <side-b-assets> <side-a|side-b> [source] [--minimum-as-of YYYY-MM-DD]
```

Assets are comma-separated. Bare IDs are players. Explicit forms are `player:<id>` and `pick:<draft-pick-id>`.

The perspective identifies the team giving that side's package and receiving the opposite package:

- `side-a`: evaluate the trade for the team giving side A and receiving side B.
- `side-b`: evaluate the trade for the team giving side B and receiving side A.

## Governed policies

The live recommendation output identifies the governing policies:

- recommendation: `trade-recommendation-v5-market-first-flexible-transition-material-loss-veto`
- strategic veto detector: `trade-strategic-veto-v4-material-protected-value-plus-flexible-transition-loss`
- flexible-slot pressure: `flexible-slot-pressure-v1-combined-relative-quartiles`
- flexible-slot coverage: `flexible-slot-coverage-v1-direct-reserved-max-value`
- flexible coverage loss: `trade-flexible-coverage-loss-v1-post-trade-legal-lineup`
- flexible pressure transition: `trade-flexible-pressure-transition-v1-post-trade-league-relative`
- post-trade depth: `trade-flexible-post-trade-depth-v1-two-team-exchange`
- protected-value flow: `trade-protected-value-flow-v1-current-valued-assets`
- protected-value materiality: `trade-protected-value-materiality-v1-25-percent-loss`
- team perspective: `trade-team-perspective-v1-explicit-owner`

The v5 recommendation policy remains market-first. Strategic evidence cannot create a preferred side or flip a market direction. A governed material-loss veto can only downgrade an otherwise directional recommendation to `HOLD`.

No hidden weighting, strategic score blending, or side flipping is applied.

Earlier recommendation and veto contracts remain versioned in the codebase for compatibility and audit history. In particular, v4 (`trade-recommendation-v4-market-first-flexible-material-loss-veto`) and strategic veto v3 (`trade-strategic-veto-v3-material-protected-value-plus-flexible-coverage-loss`) remain intact as frozen compatibility surfaces. The executable `trade recommendation` path now uses v5 and strategic veto v4.

## Evidence gates

A directional recommendation requires all governed evidence gates to be available:

- market direction
- team posture
- future draft capital
- direct positional pressure
- combined FLEX/SUPERFLEX pressure

The CLI reports each gate independently. If one or more required gates are unavailable, the action is `INCONCLUSIVE` and the unavailable gates are named.

Market direction is unavailable when the governed market-edge policy cannot classify the trade from current comparable evidence. Posture, future-capital, direct-positional-pressure, and flexible-pressure availability are inherited from their upstream governed analyzers and freshness/completeness guards.

Active FLEX/SUPERFLEX pressure requires complete neutral flexible coverage and at least four league teams. If the league has no FLEX or SUPERFLEX requirement, the flexible tier is `NO_FLEXIBLE_REQUIREMENT` and the flexible evidence gate remains available because there is no flexible weakness or transition to protect.

Team posture is currently an evidence-availability gate only. It does not independently create a veto.

The material-loss detector is evaluated only after all governed recommendation evidence is complete. If those gates are incomplete, the CLI reports:

```text
Strategic veto: NOT_EVALUATED
```

This prevents `CLEAR` from implying that a veto analysis succeeded when the evidence needed to perform it was unavailable.

## Materiality boundary

The governed materiality boundary is **25%**.

For a protected value or coverage measure:

```text
loss = max(0, before protected value - after/replacement protected value)
loss fraction = loss / before protected value
```

When the before value is zero, the loss fraction is zero.

- loss of exactly `25%` is within tolerance and does not trigger a veto;
- loss greater than `25%` is material and can trigger a veto when the associated weakness or transition condition applies.

Equivalent replacement framing: at least **75%** of the protected measure must remain. For example, `100 -> 75` clears the materiality rule; `100 -> 74` creates a `26%` material loss.

Current individual trade-asset values are required for protected future-pick and direct-position flows. Missing, stale, non-finite, or negative protected values are rejected rather than guessed or treated as zero.

Flexible protection uses governed legal lineup coverage rather than a same-position asset-flow shortcut.

## Strategic veto

After the evidence gates are complete, Butler evaluates the explicitly selected team's outgoing and incoming packages for deterministic material loss in governed protected areas and for a material transition into combined flexible pressure.

The governed veto state remains:

- `CLEAR`: no governed material-loss veto rule was triggered.
- `BLOCKED`: one or more governed material-loss veto rules were triggered.

The current governed veto rules are intentionally narrow:

1. **Low future capital protection** — when the selected team is already `LOW_FUTURE_CAPITAL`, Butler aggregates future-pick value leaving the team and future-pick value coming back. A loss greater than `25%` of outgoing future-pick value triggers a veto.
2. **Direct positional pressure protection** — when the selected team is `POSITION_PRESSURE` at `QB`, `RB`, `WR`, or `TE`, Butler aggregates outgoing player value at that position and incoming player value at the same position. A loss greater than `25%` of outgoing same-position value triggers a veto for that position.
3. **Existing combined flexible pressure protection** — when the selected team is already `FLEXIBLE_PRESSURE`, Butler recomputes the team's legal lineup after the trade. Direct QB/RB/WR/TE starters are reserved again, then the maximum legal remaining FLEX/SUPERFLEX coverage is recalculated. A drop greater than `25%` from governed pre-trade flexible coverage triggers a veto.
4. **Material transition-to-pressure protection** — when the selected team is not already `FLEXIBLE_PRESSURE`, Butler applies the trade to both trade teams, recomputes legal flexible coverage for the full league, reruns the league-relative flexible-pressure classification, and checks the selected team's post-trade tier. A veto occurs only when the selected team newly enters `FLEXIBLE_PRESSURE` **and** legal flexible coverage falls by more than `25%`.

Future-pick and direct-position replacement remain category-specific. A player received for an outgoing pick does not replenish future-pick protected value, and another-position player does not replenish a direct pressured position's same-position protected value.

Flexible protection is different by design. Legal cross-position substitution is allowed inside the flexible lineup calculation: ordinary FLEX accepts `RB`, `WR`, and `TE`; SUPERFLEX accepts `QB`, `RB`, `WR`, and `TE`. A received RB can therefore replace flexible coverage previously supplied by a WR when the post-trade lineup optimizer determines that is the best legal assignment. Direct starters are reselected before flexible coverage is measured, so the result reflects the actual legal post-trade lineup rather than a static asset-flow approximation.

Team posture alone does not trigger a veto. The detector does not infer subjective age-window, contender/rebuilder, or roster-preference strategy.

## Flexible transition semantics

The v5 transition rule closes the gap where a balanced or strong team could previously make a materially damaging trade that pushed it into the league's bottom flexible-coverage quartile without triggering a flexible veto.

The transition analysis is deliberately league-relative and two-sided:

1. reconstruct the selected trade team's post-trade roster;
2. reconstruct the opposite trade team's post-trade roster;
3. recompute legal FLEX/SUPERFLEX coverage for the entire league;
4. rerank the league under the same flexible-pressure quartile policy;
5. compare the selected team's pre-trade tier with its post-trade tier;
6. apply the same 25% materiality boundary to the selected team's legal flexible coverage loss.

Updating only the selected team is not sufficient because the opposite trade team's changed coverage can move the quartile boundary.

The transition analyzer reports one of:

- `NO_FLEXIBLE_REQUIREMENT`
- `INSUFFICIENT_EVIDENCE`
- `NO_TRANSITION`
- `TRANSITION_WITHIN_TOLERANCE`
- `MATERIAL_TRANSITION_TO_PRESSURE`

A transition veto requires both conditions:

- pre-trade tier is not `FLEXIBLE_PRESSURE` and post-trade tier is `FLEXIBLE_PRESSURE`; and
- legal flexible coverage loss is greater than `25%`.

Therefore:

- `FLEXIBLE_BALANCED -> FLEXIBLE_PRESSURE` with more than 25% loss can veto;
- `FLEXIBLE_STRENGTH -> FLEXIBLE_PRESSURE` with more than 25% loss can veto;
- exactly 25% loss does not veto even when the tier moves into pressure;
- a material flexible coverage loss that leaves the team balanced or strong does not trigger the transition veto;
- a team already under `FLEXIBLE_PRESSURE` remains governed by the existing-pressure flexible rule rather than the transition rule.

A valid selected-team assessment cannot contain both flexible veto reasons.

## Reason vocabulary and ordering

The live strategic reason codes are:

- `LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS`
- `POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS`
- `FLEXIBLE_PRESSURE_MATERIAL_POST_TRADE_COVERAGE_LOSS`
- `FLEXIBLE_MATERIAL_LOSS_TRANSITION_TO_PRESSURE`

The CLI prints each triggered veto reason with the protected measure before and after the trade, loss percentage, and the 25% materiality boundary. Multiple reasons can be reported for the same trade.

Their order is deterministic: future-capital protection first; direct positional reasons in `QB`, `RB`, `WR`, `TE` order; existing flexible-pressure protection next; transition-to-pressure protection last.

## Live flexible output

When transition analysis is evaluated, the v5 CLI reports the selected team's pre-trade and post-trade flexible tiers and the transition state, for example:

```text
Flexible pressure transition: FLEXIBLE_BALANCED -> FLEXIBLE_PRESSURE
Flexible transition state: MATERIAL_TRANSITION_TO_PRESSURE
Flexible transition coverage: 40.00 -> 20.00 (50.0% loss)
```

For a team already under flexible pressure, the CLI may also report its existing protected coverage calculation:

```text
Flexible protected coverage: 100.00 -> 70.00 (30.0% loss)
```

These are evidence outputs. They do not independently create market direction.

## Package recommendation

The live v5 package-level policy emits one of:

- `SIDE_A_PACKAGE_PREFERRED`
- `SIDE_B_PACKAGE_PREFERRED`
- `HOLD`
- `INCONCLUSIVE`

With complete evidence and a `CLEAR` veto state, directional package preference follows the governed market-edge classification.

`HOLD` has two governed meanings:

- the governed market comparison is inside the fairness band; or
- a directional market recommendation was blocked by one or more governed strategic material-loss vetoes.

`INCONCLUSIVE` means required governed evidence is incomplete or market direction is unavailable. Under v5, unavailable flexible-pressure evidence is therefore sufficient to make the recommendation `INCONCLUSIVE`; Butler does not fall back to an earlier recommendation version on the live path.

A strategic veto never converts one preferred side into the other preferred side.

## Team action

The explicit perspective policy translates the package recommendation into one of:

- `ACCEPT`
- `REJECT`
- `HOLD`
- `INCONCLUSIVE`

For the side-A team, a side-B package preference maps to `ACCEPT`; a side-A package preference maps to `REJECT`. Side B uses the exact mirrored mapping.

`HOLD` and `INCONCLUSIVE` are preserved unchanged for either perspective.

Because veto assessment is perspective-specific, the same proposed trade can have different veto evidence for side A and side B. The market direction remains shared; only the selected team's strategic protection and transition rules are assessed for that invocation.

## Scope boundary

The current live policy does not:

- blend posture, future capital, direct positional pressure, flexible pressure, transition evidence, and market edge with numeric weights;
- let strategic evidence create or reverse market direction;
- apply a posture-only veto;
- require full protected-value preservation; the governed tolerance is 25% loss;
- substitute player value for future-pick protected value;
- substitute another-position player for a directly pressured position's same-position protected value;
- treat flexible coverage as a raw sum of eligible-player values;
- use a fixed position weight inside FLEX/SUPERFLEX;
- veto a flexible tier transition at exactly 25% loss;
- veto a material flexible loss that does not move a non-pressure team into `FLEXIBLE_PRESSURE` under the transition rule;
- infer age-window, contender, or rebuilder trade strategy;
- generate a `COUNTER` recommendation;
- relax evidence completeness because a trade looks attractive;
- select a winner when governed evidence is incomplete;
- infer a team perspective when the caller did not provide one.

Any future change that introduces weighting, side flipping, a different materiality threshold, new cross-category substitution, new flexible eligibility, different league-relative transition semantics, new veto semantics, posture/age strategy, counter-offer behavior, confidence-based relaxation, or new action semantics is a recommendation-policy change and should be versioned and reviewed as such.
