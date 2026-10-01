# Lineup usage review

The lineup preview now evaluates exact-ID usage evidence before optimizing projections.
Availability holds retain priority: Questionable requires review and confirmed unavailable
players cannot be promoted. Usage does not overwrite injury designations.

## Sources and identity

- Weekly carries and targets: nflverse `stats_player_week_<season>.csv`.
- Offensive snaps and snap share: nflverse `snap_counts_<season>.csv`.
- Exact GSIS/PFR-to-Sleeper crosswalk: DynastyProcess `db_playerids.csv`.

Only the two immediately preceding regular-season weeks in the current season count.
Both sources must cover both weeks. Ambiguous identities, byes, absent observations,
and missing numeric fields cannot become zero workload. Each displayed observation
includes source URLs and the time Butler checked the files; that time is not the
publisher's update timestamp. A successful source download is cached for 15 minutes.

## Conservative review rule

For RB, WR, and TE, require manual review when all conditions hold:

1. The earlier week had at least four carries plus targets and at least 40% offensive snap share.
2. The latest week has at least 50% fewer carries plus targets.
3. Offensive snap share fell at least 20 percentage points.

This is an explicit conservative heuristic, not a statistically validated forecast.
It does not establish why usage fell or infer a confirmed depth-chart change.
Carries plus targets means opportunities, not touches. It cannot assess QB passing workload.

A held bench player is excluded from promotions. A held starter stays in the current
slot pending review. Held players are excluded from comparable projected totals;
the remaining healthy, scoreable slots can still produce proposals.

## Limits

No synthetic point bonuses or penalties are added. Ranking among remaining candidates
is still projection-based. Unavailable usage retains the proposal with an explicit
manual-review gap, rather than claiming evidence support. Public ESPN headline context
is attributed but no expert start/sit pick or consensus is extracted. NFL defensive
matchup evidence and the broader multi-factor ranking remain unimplemented.

## Close projection conflicts

A same-position RB/WR/TE bench promotion is withheld when its projected slot gain
is positive but at most one point, the current player's observed carries plus targets
rose at least 25%, and the candidate's fell at least 50%. Both earlier-week baselines
must contain at least four opportunities. This is a conservative review heuristic,
not a statistically calibrated tie-breaker. Missing usage cannot trigger this rule.
QB passing workload and cross-position comparisons are excluded.

The optimizer runs again after withholding candidates; it checks replacement proposals
until no additional candidate meets this rule. Withheld candidates stay visible as
review comparisons, with the rejected projection edge clearly identified.

All remaining swaps are projection proposals requiring manual review, even with complete
usage, because defensive matchup and expert start/sit picks are unverified. My Team and
Matchup use qualified wording rather than directing a user to make changes. Each swap
has one structured comparison with compact usage, check times, and source links.

## Validation

Focused tests cover concurrent drops, stable participation, missing weeks, wrong seasons,
postseason/current-week exclusion, ambiguous identities, duplicate rows, invalid numbers,
bench-promotion exclusion, and source failure. The staged PowerShell rendering check
verifies missing-evidence disclosure and avoids an unqualified “all keep” conclusion.
Windows packaged launch and live lineup review remain a separate acceptance step.

## NFL defensive matchup context

Each structured swap review includes the scheduled NFL opponent for the roster's
saved NFL team and the observed production by opposing QB/RB/WR/TE players against
that defense. This uses nflverse `schedules/games.csv` and the same season's weekly
player stats, joined by exact game ID, offense team, opponent team and position.
The sample includes completed regular-season games in the three weeks before the
current scoring week. Passing, rushing and receiving yards and offensive TDs are
totals across all players at that position, not per-player forecasts or fantasy
points. The game count and week range are displayed. No adjustment is made for
opponent strength, game script, or small samples.

The roster team is saved metadata, not a fresh transaction verification. A missing
team, unsupported position, bye, ambiguous schedule, missing positional rows, or
missing QB coverage marker remains unverified. Duplicate player/game or schedule
identities and missing numeric fields fail closed. The QB marker detects wholly
missing game stats; it cannot establish every positional row is complete at the
publisher. Missing coverage is never represented as zero. A checked timestamp
records the fetch/parse time, not the publisher's update time. Successful bodies
are cached for 15 minutes with bounded requests. NFL matchup context does not
change projected points, lift holds, or claim an extracted expert start/sit pick.
