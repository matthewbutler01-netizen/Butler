# League-relative roster-strength tier policy

Policy ID: `roster-strength-tier-v1-starter-total-quartiles`

This policy describes **current roster strength relative to the other teams in the same league**. It does not describe competitive results, future flexibility, contender/rebuilder posture, or trade recommendations.

## Evidence requirements

Roster-strength tiers are available only when:

- the league has at least four teams; and
- every rostered player on every team has a usable market-value snapshot under the selected source and optional minimum-as-of guard.

If any roster has missing or stale player-value evidence, the entire league returns `INSUFFICIENT_EVIDENCE`. Butler does not rank partially valued rosters against fully valued rosters.

## Ranking

Teams are ranked lexicographically, not with a weighted score:

1. usable **starter market value**; then
2. **total usable player market value**.

Higher is stronger for both dimensions.

Positional depth remains visible as descriptive context but does not change the ranking. Draft-pick value is explicitly excluded because draft capital represents future flexibility rather than current roster strength.

## Tiers

For a league with `N` teams, the outer tier target is `floor(N * 0.25)` teams on each side.

- `FRONT_ROSTER_TIER`
- `MIDDLE_ROSTER_TIER`
- `BACK_ROSTER_TIER`
- `INSUFFICIENT_EVIDENCE`

Boundary ties are preserved. A team tied on both ranking dimensions with a boundary team receives the same outer tier. If complete equality would place a team in both outer groups, Butler keeps that team in `MIDDLE_ROSTER_TIER` rather than creating contradictory labels.

## Interpretation boundary

These labels are intentionally narrow:

- `FRONT_ROSTER_TIER` does **not** mean `CONTENDER`.
- `BACK_ROSTER_TIER` does **not** mean `REBUILDER`.
- The tier does not change player values, trade fairness, market edge, or supporting evidence.
- The tier does not create buy/sell/hold, accept/reject/counter, winner, or strategy recommendations.

Competitive-performance tier and roster-strength tier remain independent governed dimensions. Any future posture model that combines them requires its own explicit policy.

## CLI

```text
butler league roster-strength <league-id> [source] [--minimum-as-of YYYY-MM-DD]
```

The command prints the policy ID, availability, each team's tier, starter value, total player value, and value-coverage diagnostics.
