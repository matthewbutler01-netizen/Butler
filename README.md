# Butler

Butler is a governed fantasy-football decision-support application. On Windows, the supported runtime/release path is the prebuilt runtime package and release tooling below. Operators do not need to generate a Gradle wrapper to run a packaged Butler release.

## Windows Runtime and Release Quick Start

### Prerequisites

- Windows PowerShell 5.1.
- Java 25 or newer available to the host. The packaged runtime contains Butler's application JARs and dependencies, but it intentionally does not contain the Gradle wrapper or Gradle toolchain.
- Git is required only when building releases from source, to bind artifacts and verification evidence to one exact commit. Packaged setup and launch do not require Git.
- Runtime data outside the source/package tree. Butler defaults to `%LOCALAPPDATA%\Butler\data`. `BUTLER_APP_DATA_DIR` may override that location only with an absolute path outside the source/package tree.

Butler's SQLite runtime data, local credentials, build output, IDE state, and Git metadata are not part of the release package.

### Create and verify an exact-HEAD release

From the repository root on an exact, clean `main`:

```text
git checkout main
git pull --ff-only origin main
git status --short
.\scripts\butler-release-acceptance.cmd
```

`git status --short` should be empty before release creation. `butler-release-acceptance.cmd` is the authoritative one-command Windows release gate. It fails closed and, in order:

1. Creates the exact-HEAD code-only source bundle (BF-769).
2. Creates the prebuilt read-runtime bundle with no runtime data or Gradle toolchain (BF-773).
3. Extracts and launches that packaged runtime, then runs the BF-768 release-security smoke checks.
4. Runs the isolated BF-786 missing-runtime-database probe, proving a package with no governed external `butler.db` fails closed without creating the database or saving league selection.
5. Runs the existing Butler Windows acceptance and diagnostics, including the GET-only BF-688 workload.
6. Runs the BF-534/BF-844 seven-page UX guardrail after BF-688, checking decision-first presentation, progressive disclosure, desktop responsiveness, absence of gambling-style pressure, and the under-five-minute manager journey without repeating the peak-load workload.
7. Writes the BF-777 release verification record only after those acceptance layers pass.
8. Runs the offline BF-778 verifier against the just-created record, re-hashing the runtime ZIP and cross-checking the checksum sidecar and BF-773 manifest.
9. Packages exactly the four BF-778-verified runtime evidence files into the BF-787 portable release-evidence archive and emits its SHA-256 sidecar.
10. Reports `BF-534 UX GUARDRAILS: PASS`, `BF-780 RELEASE SELF-VERIFICATION: PASS`, and `BF-787 RELEASE EVIDENCE ARCHIVE: PASS` only after the complete gate succeeds.

The release/acceptance path does not submit exact POST `/refresh` and does not execute a Butler or Sleeper transaction write.

### Release artifacts

Generated release evidence is written under the ignored `release-output\` directory and is named by the exact short commit:

```text
Butler-source-<shortsha>.zip
Butler-source-<shortsha>.zip.sha256
Butler-source-<shortsha>.manifest.txt
Butler-runtime-<shortsha>.zip
Butler-runtime-<shortsha>.zip.sha256
Butler-runtime-<shortsha>.manifest.txt
Butler-release-<shortsha>.verified.txt
Butler-release-evidence-<shortsha>.zip
Butler-release-evidence-<shortsha>.zip.sha256
```

The BF-777 verification record contains release metadata only: the exact commit, runtime artifact name, SHA-256, manifest name, acceptance marker, and no-runtime-data boundaries. It does not contain the Butler database, credentials, provider payloads, or user runtime data.

BF-787 packages only the BF-778-verified runtime ZIP, runtime checksum, runtime manifest, and BF-777 verification record. It does not add runtime data or publish anything. See `docs/release-evidence.md` for the portable evidence boundary and historical verification workflow.

### Deployment boundary

`Butler-runtime-<shortsha>.zip` is code/runtime-only deployment or update material for a machine that already has governed Butler runtime data. It is not a complete fresh-machine installer and it does not contain, export, restore, or recreate `butler.db`, credentials, provider payloads, or other user runtime data.

Normal packaged deployment therefore assumes that the target machine already has a governed external Butler data directory, normally `%LOCALAPPDATA%\Butler\data` or an absolute external `BUTLER_APP_DATA_DIR`. Supplying `-LeagueId` selects which persisted Butler league the app should use; the league UUID does not recreate that league's database or evidence.

BF-770 `scripts\butler-migrate-runtime-data.ps1` remains available only for moving a legacy Butler database into the governed external data location without overwriting an existing governed database. It is not the portable cross-machine transfer path.

BF-897 adds a separate private runtime-data backup/restore path for moving an existing governed Butler database to a fresh Windows host. These private backups are never part of BF-773 runtime releases, BF-777 verification records, or BF-787 release-evidence archives.

### Read-only fresh-host setup check

From an extracted runtime package, run `scripts\butler-setup-check.cmd -RuntimeZip` with the full path to the downloaded runtime ZIP. Keep its matching `.sha256` file beside the ZIP. This command is included in v0.2.0.

The check reports Windows PowerShell 5.1, Java 25 or newer (including the selected executable), ZIP checksum and extracted-file consistency, external database presence and SQLite header, and saved league UUID format. It needs no Git or Gradle, starts no server, and does not create directories, databases, or saved settings. It prints a next action for each blocker and exits nonzero. `BUTLER_APP_DATA_DIR` selects an alternate absolute data directory outside the package, matching the app launcher; otherwise it checks `%LOCALAPPDATA%\Butler\data`.

Use an unmodified extraction: changed package files or extra executable files fail the consistency check. The checksum detects corruption and mismatch, not publisher authenticity. A PASS confirms prerequisites only, not database schema, league membership, or manager-page readiness. Missing or malformed saved league selection blocks this check even though the app supports explicit first-launch league selection as described below. A fresh host still needs a private backup from an existing installation; a league UUID cannot reconstruct missing data.

### Portable private runtime-data backup and fresh-host restore

v0.2.0 includes a guided entry point: from the extracted runtime package, run `scripts\butler-setup-restore.cmd -BackupZip` followed by the full path to your private backup ZIP. Keep its `.sha256` file beside it. Selecting a backup explicitly authorizes the restore; running without `-BackupZip` makes no changes. This command uses the existing BF-897 restore safeguards and requires Java 25 or newer plus the prebuilt runtime.

The guided flow verifies the saved league against the staged database before installing any data or settings. If the backup has no saved selection, supply `-LeagueId` with the Butler UUID from the source installation, or use an existing matching saved selection. A conflicting selection blocks the restore and is preserved for review. A UUID that is absent from the backup database is rejected. This verifies league membership only, not complete schema/evidence readiness or manager-page behavior.

The default destination is `%LOCALAPPDATA%\Butler\data`. The guided entry point also honors `BUTLER_APP_DATA_DIR`, or an explicit `-DataDir` override. For a custom destination, keep `BUTLER_APP_DATA_DIR` set to that same absolute external path when checking or launching Butler. After a successful restore, run the setup check above and then launch verification. Private backups stay separate from public release artifacts.

### Verified first launch and dashboard handoff

v0.2.0 includes `scripts\butler-setup-launch.cmd -RuntimeZip` followed by the downloaded runtime ZIP path. Run this from an extracted package after restoring your private data and saved league selection. The command first runs the read-only setup check, starts Butler on an available loopback port, verifies Butler's health identity, and reads all seven manager pages, including loaded Trade Analyzer and Decision History. It makes no transaction requests.

On success, the app stays running and the command prints its dashboard URL and a command to stop that specific instance. Startup logs and process identity are saved under `%LOCALAPPDATA%\Butler\setup-runs`. The stop command refuses mismatched process identities. With `-VerifyOnly`, the command stops its own runtime after the checks instead of leaving a dashboard running. Failed checks stop only the launched process group, including descendants, and print at most 2,000 characters of startup-log context. `-StartupTimeoutSeconds` and `-RequestTimeoutSeconds` bound startup and individual requests.

These are HTTP and page-content checks. They do not establish visual accessibility or human task-completion time. First launch can perform the application's normal database initialization/migrations on the restored copy; keep your original private backup. The setup command itself does not restore or replace a database or change league selection.

### Creating a private backup

Stop Butler before creating a backup. The backup command fails closed if a live Butler run-state marker or SQLite `-wal`, `-shm`, or `-journal` sidecar exists:

```text
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File ".\scripts\butler-runtime-data-backup.ps1"
```

By default, BF-897 writes a private ZIP and SHA-256 sidecar under `%LOCALAPPDATA%\Butler\backups`. The archive contains the verified `butler.db`, its checksum and manifest, and the saved `app-league.txt` selection when present. It contains user runtime data and must be kept private; do not upload it to GitHub or attach it to a public Butler release.

Copy both the backup ZIP and its `.sha256` sidecar to the fresh Windows host. From the extracted Butler runtime package, restore into the default governed external data location with:

```text
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File ".\scripts\butler-runtime-data-restore.ps1" -BackupZip "C:\Butler-transfer\Butler-runtime-data-<timestamp>-<hash>.zip"
```

The restore verifies the archive checksum, manifest, database checksum, SQLite header, and saved league UUID before writing anything. It is fresh-host only: it refuses to overwrite an existing governed `butler.db` or a different saved Butler league selection. Use `-DataDir <absolute-external-path>` only when the target host intentionally uses an external `BUTLER_APP_DATA_DIR`.

After restore, launch the packaged Butler runtime normally. If the backup included the saved league selection, no `-LeagueId` argument is required.

### Run a packaged release

After the runtime ZIP has been verified, extract `Butler-runtime-<shortsha>.zip` into its own directory outside the Git worktree.

On a machine that already has governed Butler runtime data but has no saved league selection, the first launch must supply the Butler league id:

```text
.\scripts\butler-app.cmd -LeagueId <butler-league-id>
```

`<butler-league-id>` is Butler's exact league UUID, not the Sleeper league id. The launcher validates the UUID and saves the selection under `%LOCALAPPDATA%\Butler\app-league.txt` for later launches. This configuration selects existing persisted Butler state; it does not create or restore the league database.

Once a league is configured, subsequent launches use the saved selection:

```text
.\scripts\butler-app.cmd
```

To intentionally change the packaged app to a different Butler league, reset the saved selection and then launch again with the new Butler league UUID:

```text
.\scripts\butler-app.cmd -ResetLeague
.\scripts\butler-app.cmd -LeagueId <new-butler-league-id>
```

The packaged launcher uses the prebuilt runtime JARs. The Gradle wrapper/toolchain remains absent from the package; its fail-closed `gradlew.bat` shim only authorizes the exact internal startup probe. Runtime data remains external at `%LOCALAPPDATA%\Butler\data` unless an absolute external `BUTLER_APP_DATA_DIR` is supplied.

### Verify a saved release later

BF-778 can independently verify a saved BF-777 record without launching Butler or reading runtime data. This also works for a historical release after repository HEAD has advanced:

```text
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File ".\scripts\butler-release-verification-check.ps1" -RecordPath ".\release-output\Butler-release-<shortsha>.verified.txt"
```

A successful check ends with:

```text
BF-778 RELEASE VERIFICATION CHECK: PASS
```

The verifier requires the record, runtime ZIP, checksum sidecar, and BF-773 runtime manifest to agree on the saved commit, artifact name, SHA-256, and governed no-runtime-data boundary.

## League Intelligence Quick Start

Butler's league-intelligence workflow is designed to distinguish between data that merely exists and data that is complete, recent, and safe to use for rankings or comparisons.

A typical workflow is:

```text
butler sleeper sync-all <sleeper-league-id>
butler nflverse production-preview <season>
butler nflverse production-refresh <season>
butler league status <league-id>
butler league decision-readiness <league-id>
butler league player-evidence-readiness <league-id> <season>
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

Butler stores player evidence separately from core player identity so richer analysis can be added without destabilizing existing league imports.

- `player_profiles` holds optional canonical biographical metadata such as exact birth date and years of experience. Age is derived for a requested date rather than persisted as a number that becomes stale.
- `player_profile_snapshots` holds versioned provider-reported facts such as age and experience, including source and as-of date.
- Sleeper league sync reuses its existing NFL player-map fetch to persist reported `age` and `years_exp` snapshots when those fields are available. It does not infer a birth date from reported age and does not add another Sleeper API request.
- `player_season_production` stores versioned raw season production evidence: games played, passing/rushing/receiving production, interceptions, fumbles lost, source, and as-of date.

Season production intentionally does not store a universal fantasy-point score. Scoring rules belong in a later interpretation layer so the same raw evidence can support different league formats.

### nflverse production import

Butler imports regular-season player production from nflverse's official `stats_player` release assets. Provider `player_id` values are treated as GSIS identifiers and are reconciled to Butler/Sleeper players through the GSIS-to-Sleeper crosswalk. Butler does not fall back to player-name matching for production imports.

Use preview before refresh when inspecting a new season or identity state:

```text
butler nflverse production-preview <season>
butler nflverse production-refresh <season>
```

Both commands run the same download, schema validation, season filtering, and identity-reconciliation pipeline. Preview performs no writes. Refresh persists versioned raw production snapshots. Output reports provider rows, requested-season rows, crosswalk entries, mapped provider rows, eligible Butler players, matched players, unmatched players, and snapshot writes.

A requested season that has not yet been published upstream fails explicitly rather than silently substituting another season. Lost fumbles are persisted as the sum of nflverse's sack, rushing, and receiving lost-fumble components.

### Player-evidence readiness

Use `league player-evidence-readiness` to determine whether rostered players have the profile/age and season-production evidence required for richer downstream analysis:

```text
butler league player-evidence-readiness <league-id> <season>
butler league player-evidence-readiness <league-id> <season> --minimum-profile-as-of YYYY-MM-DD
```

The readiness states are deliberately about evidence availability only:

- `EMPTY` — the league/team has no rostered players to evaluate.
- `BLOCKED` — one entire required evidence dimension is absent: usable age evidence or requested-season production evidence.
- `PARTIAL` — both dimensions exist, but one or both are incomplete across the roster.
- `READY` — every rostered player has usable age evidence and requested-season production evidence.

Age/profile provenance is preserved rather than flattened. Exact canonical birth dates are distinguished from provider-reported ages, and experience-only evidence does not count as age evidence. When `--minimum-profile-as-of` is supplied, stale provider-reported ages remain visible in coverage diagnostics but do not satisfy the fresh-age requirement. Exact birth dates remain usable because age is derived from the canonical date instead of a stale reported number.

`READY` does **not** mean a player is good, a roster is competitive, or a strategy recommendation is safe by itself. It only means these player-evidence inputs are complete enough to be used by a later interpretation layer.

## External fantasy-football data

Butler can import dynasty player values from the open-data repository maintained by DynastyProcess:

- Project: https://github.com/dynastyprocess/data
- Player values: `files/values-players.csv`
- Cross-platform player IDs: `files/db_playerids.csv`
- Upstream license: GNU General Public License v3.0 (GPL-3.0)

Butler fetches the upstream CSV files at runtime rather than vendoring a copy of the dataset. Imported 1QB and 2QB values are persisted separately as `dynastyprocess-1qb` and `dynastyprocess-2qb`, with the upstream `scrape_date` retained as the value snapshot date. FantasyPros-to-Sleeper IDs are the primary identity mapping. When that crosswalk is missing, Butler only falls back to a unique exact player-name + position + NFL-team match present in both upstream files; ambiguous or incomplete identities remain unmatched rather than being guessed.

For nflverse production evidence, Butler fetches the official regular-season `stats_player` release CSV for the requested season and uses the DynastyProcess player-ID crosswalk for GSIS-to-Sleeper reconciliation. Production import intentionally rejects ambiguous ID mappings and never uses player-name fallback.
