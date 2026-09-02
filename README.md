# Butler

Bootstrap project for Butler Forge.

## Next Step

Generate the Gradle Wrapper:

Windows:
```text
gradle wrapper
```

Then build:
```text
.\gradlew.bat build
```

## League Intelligence Quick Start

Butler's league-intelligence workflow is designed to distinguish between data that merely exists and data that is complete, recent, and safe to use for rankings or comparisons.

A typical workflow is:

```text
butler sleeper sync-all <sleeper-league-id>
butler league status <league-id>
butler league decision-readiness <league-id>
butler league overview <league-id>
butler league team-context <league-id>
butler league team-profile <league-id>
butler league position-context <league-id>
butler league draft-capital <league-id>
butler league asset-concentration <league-id>
butler league roster-slot-context <league-id>
butler league positional-depth <league-id>
butler league franchise-readiness <league-id> --minimum-as-of 2026-09-01
butler league franchise-rank <league-id> --minimum-as-of 2026-09-01
butler trade compare <league-id> <side-a-assets> <side-b-assets> --minimum-as-of 2026-09-01
```

Source arguments remain optional where Butler can resolve the correct value source from league format. Use an explicit source when the league format is unavailable or when intentionally evaluating against a particular value source.

### League health and readiness

Use `league status` as the first diagnostic command. It summarizes the league's persisted teams and assets, value coverage, franchise readiness, movement readiness, source resolution, and any blockers that prevent downstream analysis.

Franchise readiness uses explicit states rather than treating any stored value as sufficient:

- `READY` — required franchise assets have usable value coverage and satisfy the requested recency guard.
- `PARTIAL` — some required assets are valued, but coverage is incomplete.
- `STALE` — required values exist but one or more predate `--minimum-as-of`.
- `UNAVAILABLE` — required values cannot currently be resolved.
- `EMPTY` — the league/team has no applicable persisted assets to value.

When source resolution cannot safely determine a value source, Butler reports that a source is required instead of guessing.

### Decision readiness

Use `league decision-readiness` to check what kind of evidence-backed decisions Butler can safely support without inventing strategy assumptions:

- `BLOCKED` — current-value decisions are not safe yet because core league evidence is incomplete, stale, unavailable, or unresolved.
- `CURRENT_READY` — current-value decisions are supported, but trend-aware decisions are not yet supported by comparable historical snapshots.
- `TREND_READY` — both current-value and trend-aware decision context are available.

```text
butler league decision-readiness <league-id> [source] [--minimum-as-of YYYY-MM-DD]
```

This command does not recommend trades, label teams as contenders/rebuilders, or assign buyer/seller posture. It reports which decision modes the underlying evidence can safely support and reuses Butler's deterministic next actions when more data is required.

### Recency guards

`--minimum-as-of YYYY-MM-DD` is optional. Without it, existing commands preserve their normal no-cutoff behavior. With it, Butler requires applicable values to be dated on or after the supplied date. The cutoff is inclusive.

For example:

```text
butler league status <league-id> --minimum-as-of 2026-09-01
butler league decision-readiness <league-id> --minimum-as-of 2026-09-01
butler league overview <league-id> --minimum-as-of 2026-09-01
butler league team-context <league-id> --minimum-as-of 2026-09-01
butler league team-profile <league-id> --minimum-as-of 2026-09-01
butler league position-context <league-id> --minimum-as-of 2026-09-01
butler league draft-capital <league-id> --minimum-as-of 2026-09-01
butler league asset-concentration <league-id> --minimum-as-of 2026-09-01
butler league roster-slot-context <league-id> --minimum-as-of 2026-09-01
butler league positional-depth <league-id> --minimum-as-of 2026-09-01
butler league franchise-rank <league-id> --minimum-as-of 2026-09-01
```

A value dated `2026-09-01` satisfies that cutoff; a value dated `2026-08-31` does not.

### Safe rankings and trade comparisons

Franchise rankings are only assigned when the readiness layer says the league has sufficient asset coverage to rank safely. Partial franchise values may still be shown for context, but Butler does not turn incomplete coverage into a misleading rank.

Trade comparisons follow the same rule. Butler can preserve asset values for inspection while withholding the numerical trade difference when stale or otherwise unsafe inputs would make the comparison misleading.

This is intentional: **present data is not automatically complete data, and complete data is not automatically recent enough to support a decision.**

### League overview, team context, and composite profiles

Use `league overview` for a compact league-level intelligence summary: health, safe franchise leaders when available, recent value movers when comparable history exists, and Butler's deterministic next actions.

Use `league team-context` for the neutral team-by-team board. It reports persisted player and draft-pick value, asset coverage, safe franchise rank when available, recent player-value movement, movement coverage, riser/faller counts, and next actions.

Use `league team-profile` to compose Butler's neutral team dimensions into one inspection surface. It combines usable player and draft-pick value, asset concentration, starter-value share, draft-capital coverage, and positional-depth summaries while retaining one shared source and optional recency cutoff. The profile is a composition of existing analyzers, not a new strategy score.

```text
butler league overview <league-id> [source] [--minimum-as-of YYYY-MM-DD]
butler league team-context <league-id> [source] [--minimum-as-of YYYY-MM-DD]
butler league team-profile <league-id> [source] [--minimum-as-of YYYY-MM-DD]
```

Use these outputs to answer different questions:

1. **Can Butler safely analyze this league?** — check league status/readiness.
2. **What does the league currently look like?** — inspect overview/team context.
3. **How are a team's neutral value dimensions shaped together?** — inspect the composite team profile.

Movement readiness is separate from core franchise readiness because trend analysis requires comparable historical snapshots in addition to current values.

### Neutral roster and draft-capital context

Use `league position-context` to inspect how each team's usable player value is distributed across positions. Butler reports value and coverage by position, including missing and stale counts, without declaring any position mix good or bad.

Use `league draft-capital` to inspect future draft-pick value by current owner and season. Butler reports usable pick value, coverage, stale/missing counts, and round counts without inferring rebuild windows or preferred draft strategy.

Use `league asset-concentration` to inspect how much of each team's usable player-and-pick value sits in its highest-valued assets. Butler reports top-1, top-3, and top-5 value shares plus the Herfindahl concentration index and highest-valued assets. It does not define a preferred concentration level or attach a risk grade.

Use `league roster-slot-context` to inspect how usable player value is distributed across persisted STARTER, BENCH, RESERVE, TAXI, and OTHER roster slots. It reports coverage and the starter share of usable player value without defining a preferred starter/bench allocation.

Use `league positional-depth` to inspect how value is distributed within each position. It reports player counts, usable value, top-1/top-2/top-3 value shares, and the highest-valued players at that position without assigning a depth grade or minimum player-count target.

```text
butler league position-context <league-id> [source] [--minimum-as-of YYYY-MM-DD]
butler league draft-capital <league-id> [source] [--minimum-as-of YYYY-MM-DD]
butler league asset-concentration <league-id> [source] [--minimum-as-of YYYY-MM-DD]
butler league roster-slot-context <league-id> [source] [--minimum-as-of YYYY-MM-DD]
butler league positional-depth <league-id> [source] [--minimum-as-of YYYY-MM-DD]
```

When a minimum as-of date is supplied, stale values remain visible in coverage diagnostics but are excluded from the usable value totals shown by these neutral context commands.

### Player evidence foundations

Butler now stores player evidence separately from the core player identity so richer analysis can be added without destabilizing existing league imports.

- `player_profiles` holds optional canonical biographical metadata such as exact birth date and years of experience. Age is derived for a requested date rather than persisted as a number that becomes stale.
- `player_profile_snapshots` holds versioned provider-reported facts such as age and experience, including source and as-of date.
- Sleeper league sync reuses its existing NFL player-map fetch to persist reported `age` and `years_exp` snapshots when those fields are available. It does not infer a birth date from reported age and does not add another Sleeper API request.
- `player_season_production` stores versioned raw season production evidence: games played, passing/rushing/receiving production, interceptions, fumbles lost, source, and as-of date.

Season production intentionally does not store a universal fantasy-point score. Scoring rules belong in a later interpretation layer so the same raw evidence can support different league formats.

## External fantasy-football data

Butler can import dynasty player values from the open-data repository maintained by DynastyProcess:

- Project: https://github.com/dynastyprocess/data
- Player values: `files/values-players.csv`
- Cross-platform player IDs: `files/db_playerids.csv`
- Upstream license: GNU General Public License v3.0 (GPL-3.0)

Butler fetches the upstream CSV files at runtime rather than vendoring a copy of the dataset. Imported 1QB and 2QB values are persisted separately as `dynastyprocess-1qb` and `dynastyprocess-2qb`, with the upstream `scrape_date` retained as the value snapshot date. FantasyPros-to-Sleeper IDs are the primary identity mapping. When that crosswalk is missing, Butler only falls back to a unique exact player-name + position + NFL-team match present in both upstream files; ambiguous or incomplete identities remain unmatched rather than being guessed.
