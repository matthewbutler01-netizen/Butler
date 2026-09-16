# BF-807 Priority Ordering

BF-807 orders the three BF-806 Command Center signals by explicit manager attention state.

The ordering groups are presentation-only:

1. `attention` - existing states that already mean act, refresh, stop, pending, or roster attention.
2. `review` - an already-verified lineup that is ready for manager review.
3. `neutral` - complete, no-move, unavailable, or on-demand states.

Within a group, Butler preserves the existing signal order. The rendered card number is assigned after grouping, so `01` means the first item requiring attention on that page render.

This is not a recommendation score, confidence model, probability, or new source of evidence. BF-807 does not fetch projections, call FantasyPros, add Sleeper reads, invoke an optimizer, submit a lineup, submit a waiver transaction, execute a trade, or mutate the league.
