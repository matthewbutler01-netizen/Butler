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

### Recency guards

`--minimum-as-of YYYY-MM-DD` is optional. Without it, existing commands preserve their normal no-cutoff behavior. With it, Butler requires applicable values to be dated on or after the supplied date. The cutoff is inclusive.

For example:

```text
butler league status <league-id> --minimum-as-of 2026-09-01
butler league franchise-rank <league-id> --minimum-as-of 2026-09-01
```

A value dated `2026-09-01` satisfies that cutoff; a value dated `2026-08-31` does not.

### Safe rankings and trade comparisons

Franchise rankings are only assigned when the readiness layer says the league has sufficient asset coverage to rank safely. Partial franchise values may still be shown for context, but Butler does not turn incomplete coverage into a misleading rank.

Trade comparisons follow the same rule. Butler can preserve asset values for inspection while withholding the numerical trade difference when stale or otherwise unsafe inputs would make the comparison misleading.

This is intentional: **present data is not automatically complete data, and complete data is not automatically recent enough to support a decision.**

### League overview and team context

The league overview/status stack provides a league-level view of analysis readiness. Team context adds neutral team-by-team franchise value, coverage, safe rank when available, and recent player-value movement when comparable snapshots exist.

Use these outputs to answer two different questions:

1. **Can Butler safely analyze this league?** — check league status/readiness.
2. **What does the league currently look like?** — inspect overview/team context once the relevant readiness conditions are satisfied.

Movement readiness is separate from core franchise readiness because trend analysis requires comparable historical snapshots in addition to current values.

## External fantasy-football data

Butler can import dynasty player values from the open-data repository maintained by DynastyProcess:

- Project: https://github.com/dynastyprocess/data
- Player values: `files/values-players.csv`
- Cross-platform player IDs: `files/db_playerids.csv`
- Upstream license: GNU General Public License v3.0 (GPL-3.0)

Butler fetches the upstream CSV files at runtime rather than vendoring a copy of the dataset. Imported 1QB and 2QB values are persisted separately as `dynastyprocess-1qb` and `dynastyprocess-2qb`, with the upstream `scrape_date` retained as the value snapshot date. FantasyPros-to-Sleeper IDs are the primary identity mapping. When that crosswalk is missing, Butler only falls back to a unique exact player-name + position + NFL-team match present in both upstream files; ambiguous or incomplete identities remain unmatched rather than being guessed.
