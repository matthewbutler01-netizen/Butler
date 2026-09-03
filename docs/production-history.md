# Historical Production Import

Butler can import an explicit inclusive range of nflverse regular-season production before any multi-season interpretation is attempted.

## Commands

```text
butler nflverse production-history-preview <start-season> <end-season>
butler nflverse production-history-refresh <start-season> <end-season>
```

The season range is inclusive. For example:

```text
butler nflverse production-history-preview 2022 2025
```

checks 2022, 2023, 2024, and 2025.

Use preview first when inspecting a new range. Preview runs the same nflverse schema validation and GSIS-to-Sleeper reconciliation as refresh, but writes no production snapshots.

## Identity and download behavior

The range workflow reuses Butler's existing single-season nflverse production importer. That means historical import keeps the same identity rules:

- provider `player_id` is treated as GSIS identity;
- GSIS IDs are reconciled to Sleeper IDs through the cross-platform player-ID file;
- Butler does not fall back to player-name matching;
- ambiguous identity mappings remain errors rather than guesses.

The GSIS-to-Sleeper crosswalk is downloaded once for the requested range rather than once per season.

## Partial upstream availability

A historical range can be partially available upstream. Butler reports each season separately instead of silently skipping failures.

Per-season failures are classified as:

- `DOWNLOAD` — the season asset could not be retrieved;
- `VALIDATION` — the season asset was retrieved but did not satisfy the expected data contract.

Other seasons in the requested range continue after a per-season download or validation failure. Database errors and thread interruptions are not downgraded to season failures; they stop the operation because they may affect the integrity of the local run.

The summary reports:

- seasons requested;
- seasons succeeded;
- seasons failed;
- whether the range completed fully;
- matched player-seasons;
- snapshots written;
- per-season match/write counts;
- per-season failure details.

## Why this exists

One season is not enough evidence for a defensible aging curve or longitudinal production model. Historical production import exists to build the multi-season evidence base first.

This workflow does **not** itself calculate:

- aging curves;
- age-adjusted scores;
- development or decline labels;
- fantasy-point grades;
- player rankings;
- team strategy recommendations.

Those belong in later interpretation layers after Butler can demonstrate that the underlying exact-age and multi-season production coverage is sufficient and inspectable.

## Related commands

```text
butler nflverse production-preview <season>
butler nflverse production-refresh <season>
butler league production-context <league-id> [season]
butler league age-production-context <league-id> [season] [--age-as-of YYYY-MM-DD] [--minimum-profile-as-of YYYY-MM-DD]
```
