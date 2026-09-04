# Flexible-slot coverage evidence

Butler now has a neutral FLEX/SUPERFLEX coverage layer for lineup-aware trade evidence. This layer measures roster coverage only. It does **not** assign a pressure tier, emit a veto, or change a trade recommendation.

## Governed policies

- Flexible-slot eligibility: `trade-flexible-slot-eligibility-v1-explicit-lineup`
- Flexible-slot coverage: `flexible-slot-coverage-v1-direct-reserved-max-value`
- Upstream lineup interpretation: `lineup-requirements-v1-direct-plus-flex-exposure`

## Eligibility

Eligibility is explicit and mirrors the governed lineup slot meaning:

- `FLEX`: RB, WR, TE
- `SUPERFLEX`: QB, RB, WR, TE

FLEX and SUPERFLEX counts remain separate inputs. QB is never treated as eligible for ordinary FLEX.

No fractional position allocation, positional multiplier, or hidden QB premium is applied.

## Direct starters are reserved first

Before measuring flexible-slot coverage, Butler reserves the highest-current-value players needed to satisfy each direct QB/RB/WR/TE starter requirement.

A reserved direct starter cannot also satisfy FLEX or SUPERFLEX coverage. This prevents the same player from being double-counted across direct and flexible lineup slots.

If a roster has fewer players than a direct starter requirement, every available player at that position is reserved and the uncovered direct slot remains uncovered. Butler does not move a FLEX-eligible player into a different direct position.

## Flexible coverage measurement

After direct starters are reserved, Butler considers the remaining eligible players and computes the maximum current market value that can legally cover the league's FLEX and SUPERFLEX slots without reusing a player.

The optimization is eligibility-constrained only:

- at most the configured number of SUPERFLEX slots may be occupied by QBs;
- RB/WR/TE may occupy either FLEX or SUPERFLEX;
- a lower-value QB is not forced into SUPERFLEX when a higher-value non-QB is a legal better coverage choice;
- no age, production, draft capital, team posture, positional scarcity multiplier, or subjective lineup preference is blended into the measurement.

The team evidence reports:

- direct required slots;
- direct covered slots;
- current value reserved for direct starters;
- total flexible slots;
- flexible covered slots;
- flexible unfilled slots;
- maximum legal flexible coverage value;
- total current value of remaining players eligible for the active flexible slots.

## Evidence completeness

Unknown lineup-slot semantics fail closed.

When FLEX is active, complete current value coverage is required for rostered RB/WR/TE players because those positions can affect the legal FLEX solution. QB value completeness is not required solely for ordinary FLEX because QB is not FLEX-eligible.

When SUPERFLEX is active, complete current value coverage is required for rostered QB/RB/WR/TE players because all four positions can affect the legal SUPERFLEX solution.

A league with no FLEX or SUPERFLEX slots has neutral zero flexible-slot coverage and does not require flexible-slot value evidence.

## Scope boundary

This evidence does not currently define `FLEX_PRESSURE`, `SUPERFLEX_PRESSURE`, or any other weakness tier. It does not change direct positional-pressure tiers and is not yet consumed by the live v3 trade recommendation veto.

A separately governed policy is required before Butler can decide what constitutes weak flexible-slot coverage and before that weakness can affect `ACCEPT`, `REJECT`, `HOLD`, or `INCONCLUSIVE`.
