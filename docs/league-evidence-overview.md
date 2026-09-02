# League Evidence Overview

Butler exposes the independent readiness dimensions required before richer league analysis:

```text
butler league evidence-overview <league-id> [season]
butler league evidence-overview <league-id> [season] --minimum-value-as-of YYYY-MM-DD
butler league evidence-overview <league-id> [season] --minimum-profile-as-of YYYY-MM-DD
butler league evidence-overview <league-id> [season] --minimum-value-as-of YYYY-MM-DD --minimum-profile-as-of YYYY-MM-DD
```

For Sleeper-synced leagues, the season may be omitted when Butler has persisted the league season. An explicit season remains available for manual, historical, or override workflows. Butler does not guess a season from the current calendar year.

## Independent readiness dimensions

The overview reports two separate evidence families and deliberately does not combine them into one score.

### Value and movement decision readiness

This reuses Butler's existing league decision-readiness states:

- `BLOCKED` — current-value decisions are not safe.
- `CURRENT_READY` — current values are safe, but comparable trend history is not ready.
- `TREND_READY` — current values and comparable trend history are ready.

`--minimum-value-as-of` applies only to the value/readiness side of the report.

### Player-evidence readiness

This reuses Butler's player-evidence readiness states:

- `EMPTY` — no rostered players are present.
- `BLOCKED` — an entire required evidence dimension is absent.
- `PARTIAL` — age/profile and production evidence both exist, but coverage is incomplete.
- `READY` — every rostered player has usable age evidence and requested-season production evidence.

`--minimum-profile-as-of` applies only to provider-reported profile evidence. Exact canonical birth dates remain usable because age is derived from the birth date rather than a stale reported age.

## Interpretation boundary

A league may have `READY` player evidence while value-based decision readiness remains `BLOCKED`, or the reverse. Butler preserves that distinction intentionally.

The evidence overview does not:

- score players;
- grade teams;
- label contenders, rebuilders, buyers, or sellers;
- infer strategy;
- combine readiness dimensions into a weighted score.

The command answers a narrower question: **which evidence families are currently safe to use?**
