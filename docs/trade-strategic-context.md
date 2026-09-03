# Trade strategic context

Butler's strategic trade surface presents independently governed evidence dimensions around the same trade package. These dimensions are intentionally not blended into one hidden score.

## Mixed asset surface

`trade strategic-context` supports both players and draft picks:

```text
butler trade strategic-context <league-id> <season> <side-a-assets> <side-b-assets> [source] [--minimum-as-of YYYY-MM-DD]
```

Assets use the same grammar as `trade compare`:

- bare IDs are players;
- `player:<id>` explicitly names a player;
- `pick:<draft-pick-id>` names a draft pick;
- comma-separated assets may mix players and picks on either side.

Every trade side must resolve to exactly one current fantasy team. Player roster ownership and current draft-pick ownership are both used for that identity check. A package spanning multiple fantasy teams fails closed instead of receiving misleading strategic context.

## Market value and freshness

Persisted player and draft-pick market values remain the authoritative basis for mixed-package totals, coverage, symmetric fairness gap, the governed 5% market-fairness band, and market-edge direction.

Missing market values suppress comparability. When `--minimum-as-of` is supplied, a stale valued asset also suppresses comparability. Strategic context does not manufacture a fairness result from incomplete or stale market evidence.

The optional source and freshness boundary are carried through the roster-strength, future-capital, and positional-pressure evidence used alongside the trade so those dimensions do not silently rely on older value snapshots than the trade itself.

A malformed `--minimum-as-of` flag without a date is rejected rather than being interpreted as a market-value source.

## Age-outlook evidence

Governed age-outlook flags remain player-specific descriptive evidence. They do not alter market value, fairness, market edge, team posture, future capital, or positional pressure.

Draft picks do not receive fabricated aging evidence. Mixed-asset strategic context therefore treats player age evidence as a separate optional player dimension rather than pretending every asset shares the same evidence model.

## Neutral roster and production context

Current roster structure, positional depth, concentration, player/draft-pick asset totals, and raw production remain inspectable evidence rather than a hidden weighted score.

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

## Positional pressure

Strategic trade context also exposes governed positional-pressure evidence for both teams across the four core positions: `QB`, `RB`, `WR`, and `TE`.

The CLI prints:

- the positional-pressure policy ID;
- the lineup policy ID;
- league FLEX and SUPERFLEX slot counts;
- each team's pressure tier at QB/RB/WR/TE;
- starter-coverage market value by position;
- total position market value; and
- valued, stale, and missing player counts for that position.

The same market-value source and optional `--minimum-as-of` boundary used for the trade are applied to positional pressure. A strategic report fails closed if trade and positional-pressure evidence disagree on league, value source, freshness boundary, or team identity.

Positional pressure is descriptive context only. A weak position does not automatically make acquiring that position correct, and a strong position does not automatically make trading from it correct. No hidden need multiplier is applied to trade value, fairness, market edge, team posture, or future capital.

## Identity and evidence guards

Strategic trade context fails closed on incompatible league, season, market-value source, fantasy-team ownership, team ID, team name, or stale/incomplete evidence where the governed component requires complete evidence.

## Current decision boundary

The assembled mixed-asset trade context can now describe:

- what player/draft-pick packages are worth on the persisted market;
- whether all trade values are present and meet an optional freshness boundary;
- whether the market-value gap falls inside the 5% fairness band;
- which side has the market-value edge when outside that band;
- player-specific age evidence where applicable;
- each participating team's roster/production context;
- each team's governed contender/mixed/rebuilder posture;
- each team's governed future draft-capital tier; and
- each team's governed QB/RB/WR/TE positional-pressure context under the league's lineup configuration.

Butler still does **not** convert those dimensions into `ACCEPT`, `REJECT`, or `COUNTER`, a trade winner, a buy/sell instruction, or a dynasty recommendation. Any such behavior requires separately governed policy defining how team posture, positional need, future capital, market fairness, player production, and supporting evidence interact.
