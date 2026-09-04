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

- recommendation: `trade-recommendation-v3-market-first-material-loss-veto`
- strategic veto detector: `trade-strategic-veto-v2-material-protected-value-loss`
- protected-value flow: `trade-protected-value-flow-v1-current-valued-assets`
- protected-value materiality: `trade-protected-value-materiality-v1-25-percent-loss`
- team perspective: `trade-team-perspective-v1-explicit-owner`

The v3 recommendation policy remains market-first. Strategic evidence cannot create a preferred side or flip a market direction. A governed material-loss veto can only downgrade an otherwise directional recommendation to `HOLD`.

No hidden weighting, strategic score blending, or side flipping is applied.

The earlier presence-based detector, `trade-strategic-veto-v1-explicit-weakness-protection`, remains versioned in the codebase for audit/history but is no longer the live `trade recommendation` detector.

## Evidence gates

A directional recommendation requires all governed evidence gates to be available:

- market direction
- team posture
- future draft capital
- positional pressure

The CLI reports each gate independently. If one or more required gates are unavailable, the action is `INCONCLUSIVE` and the unavailable gates are named.

Market direction is unavailable when the governed market-edge policy cannot classify the trade from current comparable evidence. Posture, future-capital, and positional-pressure availability are inherited from their upstream governed analyzers and their freshness/completeness guards.

Team posture is currently an evidence-availability gate only. It does not independently create a veto.

The material-loss detector is evaluated only after all governed recommendation evidence is complete. If those gates are incomplete, the CLI reports:

```text
Strategic veto: NOT_EVALUATED
```

This prevents `CLEAR` from implying that a veto analysis succeeded when the evidence needed to perform it was unavailable.

## Protected-value materiality

The v2 strategic detector compares protected outgoing value with protected incoming replacement value for the selected team's already-weak areas.

For a protected category:

```text
loss = max(0, outgoing protected value - incoming protected value)
loss fraction = loss / outgoing protected value
```

When outgoing protected value is zero, the loss fraction is zero.

The materiality boundary is **25%**:

- loss of exactly `25%` is `WITHIN_TOLERANCE` and does not trigger a veto;
- loss greater than `25%` is `MATERIAL_LOSS` and can trigger a veto when the associated weakness tier applies.

Equivalent replacement framing: incoming protected value must preserve at least **75%** of the outgoing protected value. For example, sending `100` protected value and receiving `75` clears the materiality rule; receiving `74` creates a `26%` material loss.

Protected-value analysis uses current individual trade-asset market values. Missing, stale, non-finite, or negative protected values are rejected rather than guessed or treated as zero.

## Strategic veto

After the evidence gates are complete, Butler evaluates the explicitly selected team's outgoing and incoming packages for deterministic material protected-value loss.

The underlying governed veto state remains:

- `CLEAR`: no governed material-loss veto rule was triggered.
- `BLOCKED`: one or more governed material-loss veto rules were triggered.

The current governed veto rules are intentionally narrow:

1. **Low future capital protection** — when the selected team is already `LOW_FUTURE_CAPITAL`, Butler aggregates future-pick value leaving the team and future-pick value coming back. A loss greater than `25%` of outgoing future-pick value triggers a veto.
2. **Positional pressure protection** — when the selected team is `POSITION_PRESSURE` at `QB`, `RB`, `WR`, or `TE`, Butler aggregates outgoing player value at that position and incoming player value at the same position. A loss greater than `25%` of the outgoing same-position value triggers a veto for that position.

The replacement comparison is category-specific. A player received for an outgoing pick does not replenish future-pick protected value, and a player at another position does not replenish the protected same-position value.

Middle/balanced tiers do not trigger these vetoes. Team posture alone does not trigger a veto. The detector does not infer age-window strategy, contender/rebuilder timing, subjective roster preference, or cross-position substitution.

The CLI prints each triggered veto reason with outgoing protected value, incoming replacement value, loss percentage, and the 25% materiality boundary. Multiple reasons can be reported for the same trade. Their order is deterministic: future-capital protection first, then positional reasons in `QB`, `RB`, `WR`, `TE` order.

## Package recommendation

The v3 package-level policy emits one of:

- `SIDE_A_PACKAGE_PREFERRED`
- `SIDE_B_PACKAGE_PREFERRED`
- `HOLD`
- `INCONCLUSIVE`

With complete evidence and a `CLEAR` veto state, directional package preference follows the governed market-edge classification.

`HOLD` has two governed meanings:

- the governed market comparison is inside the fairness band; or
- a directional market recommendation was blocked by a governed strategic material-loss veto.

`INCONCLUSIVE` means required governed evidence is incomplete or market direction is unavailable.

A strategic veto never converts one preferred side into the other preferred side.

## Team action

The explicit perspective policy translates the package recommendation into one of:

- `ACCEPT`
- `REJECT`
- `HOLD`
- `INCONCLUSIVE`

For the side-A team, a side-B package preference maps to `ACCEPT`; a side-A package preference maps to `REJECT`. Side B uses the exact mirrored mapping.

`HOLD` and `INCONCLUSIVE` are preserved unchanged for either perspective.

Because veto assessment is perspective-specific, the same proposed trade can have different veto evidence for side A and side B. The market direction remains shared; only the selected team's strategic protection rules are assessed for that invocation.

## Scope boundary

The current policy does not:

- blend posture, future capital, positional pressure, and market edge with numeric weights;
- let strategic evidence create or reverse market direction;
- apply a posture-only veto;
- require full protected-value preservation; the governed tolerance is 25% loss;
- substitute player value for future-pick protected value;
- substitute other-position player value for a pressured position's protected value;
- model FLEX or SUPERFLEX substitution inside the veto;
- infer age-window, contender, or rebuilder trade strategy;
- generate a `COUNTER` recommendation;
- relax evidence completeness because a trade looks attractive;
- select a winner when governed evidence is incomplete;
- infer a team perspective when the caller did not provide one.

Any future change that introduces weighting, side flipping, a different materiality threshold, cross-category substitution, FLEX/SUPERFLEX substitution, new veto semantics, posture/age strategy, counter-offer behavior, confidence-based relaxation, or new action semantics is a recommendation-policy change and should be versioned and reviewed as such.
