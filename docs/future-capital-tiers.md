# Future draft-capital tiers

Butler treats future draft capital as an independent future-flexibility dimension. It is not part of current roster strength, team posture, market fairness, or a trade recommendation.

## Governed policy

Policy ID: `future-capital-tier-v1-draft-value-quartiles`

The league-relative ranking uses total usable market value of each team's persisted future draft picks. No weighted score is created and pick timing is not silently converted into a multiplier.

For leagues with at least four teams and complete usable pick-value coverage:

- top 25% by total draft-pick market value: `HIGH_FUTURE_CAPITAL`
- bottom 25%: `LOW_FUTURE_CAPITAL`
- all others: `MIDDLE_FUTURE_CAPITAL`

The outer-tier count is the floor of 25% of league teams, with at least one team when the policy is available. Ties at either quartile boundary are preserved, so a tied group may make an outer tier larger than exactly 25%. If the high and low boundaries collapse to the same value, every team is classified `MIDDLE_FUTURE_CAPITAL`.

## Evidence requirements

The policy fails closed as `INSUFFICIENT_EVIDENCE` when:

- the league has fewer than four teams;
- no future draft picks are persisted for the league; or
- any persisted future pick is missing a usable value or is stale under the requested minimum-as-of rule.

A team with zero owned future picks is valid evidence and has zero future-capital value. That is different from a league with no persisted future-pick inventory at all.

## Timing context

Butler preserves draft capital by season and round as descriptive context. Near-term and later picks are visible to downstream analysis, but v1 does not apply a recency multiplier, round preference, or strategic weighting beyond the persisted market values themselves.

## Interpretation boundary

`HIGH_FUTURE_CAPITAL` does not mean a team should rebuild. `LOW_FUTURE_CAPITAL` does not mean a team should contend. Future capital does not modify `CONTENDER`, `MIDDLE_OR_MIXED`, or `REBUILDER` posture.

Likewise, future capital does not by itself produce a trade winner, accept/reject/counter recommendation, buy/sell instruction, or player-value adjustment. Those behaviors require separate governed decision policy.
