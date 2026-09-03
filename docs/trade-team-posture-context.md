# Trade team posture context

`butler trade supporting-evidence` now includes governed team posture for each participating fantasy team alongside the existing player-only trade evidence.

The command still uses the same syntax:

```text
butler trade supporting-evidence <league-id> <season> <side-a-player-ids> <side-b-player-ids> [source]
```

## What posture adds

For each trade side, Butler reports:

- governed team posture
- competitive-performance tier
- current-roster-strength tier
- the existing roster, production, market-value, fairness, market-edge, and player age-outlook evidence

The posture policy remains `team-posture-v1-tier-agreement`:

- `FRONT_TIER` + `FRONT_ROSTER_TIER` -> `CONTENDER`
- `BACK_TIER` + `BACK_ROSTER_TIER` -> `REBUILDER`
- any other sufficient combination -> `MIDDLE_OR_MIXED`
- insufficient evidence in either dimension -> `INSUFFICIENT_EVIDENCE`

## Separation of concerns

Team posture does not alter persisted player market values, trade-side totals, market-value completeness, symmetric fairness gap, 5% fairness classification, market-edge direction, or supporting age evidence.

Posture is strategic context only. The current trade surface still does **not** output a winner, accept/reject/counter recommendation, trade grade, hidden score, or value adjustment.

## Identity and availability guards

The posture wrapper requires the trade context and posture report to agree on league, season, roster value source, team IDs, and team names. Mismatches fail closed instead of attaching posture to the wrong trade side.

A posture report can be unavailable even when market-value trade comparison is complete. That does not make the market comparison incomplete; the dimensions remain independent.

## Draft capital

Draft-pick value is visible in neutral roster context but is not part of the current-roster-strength tier or posture calculation. It represents future flexibility and remains a separate strategic dimension until explicitly governed.
