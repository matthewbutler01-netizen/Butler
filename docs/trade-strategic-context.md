# Trade strategic context

Butler's player-only `trade supporting-evidence` surface now presents several independently governed evidence dimensions around the same trade package. These dimensions are intentionally not blended into one hidden score.

## Market value

Persisted player market values remain the authoritative basis for trade-package totals, coverage, symmetric fairness gap, the governed 5% market-fairness band, and market-edge direction.

Missing market value keeps the trade incomplete. Supporting context cannot manufacture a missing value or fairness result.

## Age-outlook evidence

Governed age-outlook flags remain per-player descriptive evidence. They do not alter market value, fairness, market edge, team posture, or future capital.

## Neutral roster and production context

The trade surface preserves current roster structure, positional depth, concentration, player/draft-pick asset totals, and raw production coverage. These dimensions remain inspectable evidence rather than a hidden weighted score.

## Team posture

Policy: `team-posture-v1-tier-agreement`.

Team posture is derived only from agreement between independently governed competitive-performance and current-roster-strength tiers:

- front competitive tier + front roster tier -> `CONTENDER`
- back competitive tier + back roster tier -> `REBUILDER`
- all other sufficient combinations -> `MIDDLE_OR_MIXED`
- insufficient input evidence -> `INSUFFICIENT_EVIDENCE`

Neither dimension overrides the other.

## Future draft capital

Policy: `future-capital-tier-v1-draft-value-quartiles`.

Future capital is a separate future-flexibility dimension based on league-relative total usable draft-pick market value:

- `HIGH_FUTURE_CAPITAL`
- `MIDDLE_FUTURE_CAPITAL`
- `LOW_FUTURE_CAPITAL`
- `INSUFFICIENT_EVIDENCE`

Draft capital does not change current roster strength or team posture. Season/round timing remains visible descriptive context and is not silently weighted.

## Identity and evidence guards

Trade context fails closed on incompatible league, season, market-value source, team ID, or team-name evidence. Governed dimensions also retain their own coverage requirements.

## Current decision boundary

The assembled trade context can now describe:

- what the two player packages are worth on the persisted market;
- whether the market-value gap falls inside the 5% fairness band;
- which side has the market-value edge when outside that band;
- relevant player age-outlook evidence;
- each participating team's roster/production context;
- each team's governed contender/mixed/rebuilder posture; and
- each team's governed future draft-capital tier.

Butler still does **not** convert those dimensions into `ACCEPT`, `REJECT`, or `COUNTER`, a trade winner, a buy/sell instruction, or a dynasty recommendation. Any such behavior requires a separately governed decision policy defining how team posture, positional need, future capital, market fairness, player production, and supporting evidence interact.
