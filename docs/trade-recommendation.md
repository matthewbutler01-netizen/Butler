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

The recommendation output identifies the governing policies:

- recommendation: `trade-recommendation-v2-market-first-strategic-veto`
- strategic veto detector: `trade-strategic-veto-v1-explicit-weakness-protection`
- team perspective: `trade-team-perspective-v1-explicit-owner`

The v2 recommendation policy remains market-first. Strategic evidence cannot create a preferred side or flip a market direction. A governed strategic veto can only downgrade an otherwise directional recommendation to `HOLD`.

No hidden weighting, strategic score blending, or side flipping is applied.

## Evidence gates

A directional recommendation requires all governed evidence gates to be available:

- market direction
- team posture
- future draft capital
- positional pressure

The CLI reports each gate independently. If one or more required gates are unavailable, the action is `INCONCLUSIVE` and the unavailable gates are named.

Market direction is unavailable when the governed market-edge policy cannot classify the trade from current comparable evidence. Posture, future-capital, and positional-pressure availability are inherited from their upstream governed analyzers and their freshness/completeness guards.

Team posture is currently an evidence-availability gate only. It does not independently create a veto.

## Strategic veto

After the evidence gates are complete, Butler evaluates the explicitly selected team's outgoing and incoming packages for deterministic strategic conflicts.

The current veto detector can return:

- `CLEAR`: no governed veto rule was triggered.
- `BLOCKED`: one or more governed veto rules were triggered.

The current governed veto rules are intentionally narrow:

1. **Low future capital protection** — if the selected team is already classified `LOW_FUTURE_CAPITAL`, sends one or more future picks, and receives no future pick, the trade is vetoed.
2. **Positional pressure protection** — if the selected team is classified `POSITION_PRESSURE` at a position, sends a player at that position, and receives no player at the same position, the trade is vetoed for that position.

Middle/balanced tiers do not trigger these vetoes. The detector does not infer age-window strategy, contender/rebuilder timing, or subjective roster preference.

The CLI prints each triggered veto reason. Multiple reasons can be reported for the same trade.

## Package recommendation

The v2 package-level policy emits one of:

- `SIDE_A_PACKAGE_PREFERRED`
- `SIDE_B_PACKAGE_PREFERRED`
- `HOLD`
- `INCONCLUSIVE`

With complete evidence and a `CLEAR` veto state, directional package preference follows the governed market-edge classification.

`HOLD` has two governed meanings:

- the governed market comparison is inside the fairness band; or
- a directional market recommendation was blocked by a governed strategic veto.

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
- infer age-window, contender, or rebuilder trade strategy;
- generate a `COUNTER` recommendation;
- relax evidence completeness because a trade looks attractive;
- select a winner when governed evidence is incomplete;
- infer a team perspective when the caller did not provide one.

Any future change that introduces weighting, side flipping, new veto semantics, posture/age strategy, counter-offer behavior, confidence-based relaxation, or new action semantics is a recommendation-policy change and should be versioned and reviewed as such.
