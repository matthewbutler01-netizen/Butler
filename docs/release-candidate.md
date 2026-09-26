# Butler v0.3.0 Release

## Release scope

v0.3.0 completes Butler's Windows MVP zero-to-Dashboard onboarding boundary. A brand-new Windows
profile can start from the verified runtime package, provide a Sleeper username
and current Sleeper league ID, and reach a verified Butler Dashboard without
manual SQLite work, a pre-existing Butler database, or an internal Butler league
UUID.

The packaged new-league flow reuses Butler's existing governed capabilities:

- runtime/package integrity and Java checks;
- existing Sleeper league, roster, lineup, scoring, draft-pick, and value import;
- exact requesting-user account, league, and roster discovery/binding;
- current matchup plus governed waiver/My Team evidence hydration;
- staged database creation before fresh-profile commit; and
- the existing seven-page first-launch verifier and Dashboard handoff.

The Sleeper username is required so Butler can prove exactly which roster belongs
to the requesting manager. It does not infer roster ownership from team names,
players, or other heuristics.

Release acceptance must prove that:

- the downloaded runtime ZIP matches its SHA-256 sidecar;
- the extracted prebuilt runtime launches without a Gradle wrapper or toolchain;
- BF-768 release-security checks pass;
- BF-773 packaged-runtime launch acceptance passes;
- the existing private-backup restore path remains intact;
- the fresh-profile new-league flow refuses existing Butler data/settings;
- new-league setup stages data before committing `butler.db` and the saved league;
- the exact bound Sleeper manager/league/roster is live-verified;
- all seven manager pages return HTTP 200 from the isolated fresh profile; and
- failure cleanup preserves unrelated processes and existing settings.

The published runtime remains code/runtime-only. It contains no Butler database,
credentials, provider payloads, logs, Git metadata, or user runtime data. New
league data is acquired only when the operator explicitly runs the packaged
new-league setup.

## Exact-commit release gate

v0.3.0 retains the v0.2.0 release baseline and its external runtime-data boundary,
including JUnit 6.1.3 with strict dependency verification and the BF-534
seven-page UX guardrail.

Before `v0.3.0` may be published, its exact clean main commit must pass:

1. `scripts\butler-release-acceptance.cmd`;
2. the exact-head BF-885/BF-912 Fast Lane journey;
3. `scripts\butler-mvp-completion-acceptance.cmd`, which builds the exact-HEAD
   runtime and performs real fresh-profile Sleeper onboarding under an isolated
   temporary Windows profile; and
4. the human under-five-minute manager task recorded by the accessibility review.

The MVP completion acceptance must use the packaged runtime, the requesting
manager's Sleeper username, and the current Sleeper league ID. It runs with
`-VerifyOnly`, verifies all seven manager pages, stops its owned test runtime,
and removes only its isolated temporary profile. It does not use or replace the
operator's existing Butler profile.

Publication must use source, runtime, verification-record, and release-evidence
artifacts generated for that same exact commit. Browser spot-checks and the human
task supplement automated HTTP checks; they do not claim full WCAG conformance.

## Safety boundary

Fresh onboarding writes Butler-local data and evidence only. It does not submit a
lineup, waiver claim, trade, FAAB change, or any other Sleeper transaction. The
existing restore/migration paths remain separate, and fresh setup refuses an
existing Butler database or saved league selection instead of overwriting them.

The runtime package preserves the fail-closed Gradle shim and external
runtime-data boundary. A runtime ZIP by itself still contains no Butler database;
data creation occurs only through an explicit governed setup/restore action.

## Release history

`v0.2.0` was published from exact commit
`a9f76be71822a39b75771c4c3d6f0eccac7a76e8`. It added read-only setup checks,
guided private-backup restore with league-membership validation, and verified
first launch for an existing Butler installation.

`v0.1.1` was published from exact commit
`4eb714bdf07cc1b63910bff7f7e51570e3d38155`, adding JUnit 6.1.3 and the
BF-534 seven-page UX guardrail. The preceding `v0.1.0` stable release used
`6eefc53a75608ca5c97f18a6d0c6c966be783626` after the RC4 fresh-package soak.

The v0.1.0 release-candidate series established the prebuilt direct-Java runtime,
the fail-closed Gradle shim, Windows watchdog/process ownership, package
portability, and release-evidence gates that v0.2.0 and v0.3.0 retain.

## Publication boundary

Repository release documentation and acceptance commands generate and verify
local artifacts only. They do not create a Git tag, create or modify a GitHub
Release, upload artifacts, alter an existing runtime profile, or authorize a Butler or Sleeper transaction write. Publishing a release remains a separate
explicit operation after the exact-head gates pass.
