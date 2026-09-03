# Age + Production Context

Butler can place player age evidence next to raw season production without pretending that an aging model already exists.

## Command

```text
butler league age-production-context <league-id> [season] [--age-as-of YYYY-MM-DD] [--minimum-profile-as-of YYYY-MM-DD]
```

When `season` is omitted, Butler uses the persisted league season. An explicit season remains available for manual or historical analysis.

`--age-as-of` controls the date used to derive age from an exact birth date. If omitted, Butler uses the current UTC date.

`--minimum-profile-as-of` applies only to provider-reported profile snapshots. Exact canonical birth dates remain usable because Butler derives age from the birth date rather than treating a reported age as permanently current.

## What the command reports

For each rostered player, Butler keeps these facts separate:

- age, when usable profile evidence exists;
- age provenance (`EXACT_BIRTH_DATE`, `PROVIDER_REPORTED`, or unavailable);
- whether a season-production snapshot exists;
- games played;
- passing yards, passing touchdowns, and interceptions per game;
- rushing yards and rushing touchdowns per game;
- receptions, receiving yards, and receiving touchdowns per game;
- fumbles lost per game.

League and team output also reports joint age+production coverage and how many players have production rates available.

## Zero games are not missing production

A valid production snapshot with zero games played remains production evidence. Butler does not divide by zero and does not invent per-game rates for that player. Rate fields remain unavailable.

A missing production snapshot is also not coerced to zero. Missing evidence stays visibly missing.

This distinction matters because:

```text
snapshot exists != player appeared in a game != per-game rate is calculable
```

## What this is not

`age-production-context` does **not** apply:

- an aging curve;
- a peak-age assumption;
- an age-adjusted score;
- a universal fantasy-points formula;
- a breakout/decline label;
- a player grade;
- a contender/rebuilder label;
- a trade or roster recommendation.

Those require an interpretation model supported by empirical evidence. Butler keeps the age and production facts inspectable first so a later model can be fitted and governed instead of hard-coded from intuition.

## Related commands

```text
butler league age-context <league-id> [--age-as-of YYYY-MM-DD] [--minimum-profile-as-of YYYY-MM-DD]
butler league production-context <league-id> [season]
butler league player-evidence-profile <league-id> [season] [--age-as-of YYYY-MM-DD] [--minimum-profile-as-of YYYY-MM-DD]
butler league player-evidence-readiness <league-id> [season] [--minimum-profile-as-of YYYY-MM-DD]
```

Use `age-context` when only profile/age shape matters, `production-context` for raw season totals, `player-evidence-profile` for team-level composition, and `age-production-context` when the player-level age and production-rate evidence needs to be inspected together.
