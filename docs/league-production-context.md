# League Production Context

Butler exposes scoring-neutral season production context from persisted raw player snapshots:

```text
butler league production-context <league-id> [season]
```

For leagues with persisted season metadata, season may be omitted. An explicit season remains available for historical or override workflows.

The report is organized by team and position and includes:

- rostered-player production coverage;
- player-games represented;
- passing yards, passing touchdowns, and interceptions;
- rushing yards and rushing touchdowns;
- receptions, receiving yards, and receiving touchdowns;
- fumbles lost;
- earliest/latest snapshot as-of dates represented on the team;
- exact rostered players missing requested-season production evidence.

Butler intentionally does **not** convert these raw statistics into one fantasy-point total or one cross-position production score. Scoring rules vary by league, and raw QB/RB/WR/TE production is not treated as directly comparable without an explicit interpretation layer.

Production context is descriptive evidence only. It does not grade players, rank teams, define positional strength, infer career arcs, or recommend contender/rebuilder/buyer/seller strategy.
