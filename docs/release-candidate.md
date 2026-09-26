# Butler v0.2.0 Release

## Release scope

v0.2.0 adds a packaged path for moving an existing Butler installation to a fresh
Windows profile: read-only setup checks, explicitly selected private-backup restore
with league-membership validation, and verified first launch with a dashboard
handoff and an instance-specific stop command.

Release acceptance must prove that:

- its downloaded runtime ZIP matched the published SHA-256 sidecar;
- the extracted prebuilt runtime launched without a Gradle wrapper or toolchain;
- BF-768 release-security checks passed;
- BF-773 packaged-runtime launch acceptance passed; and
- all seven manager pages return HTTP 200 against governed external runtime data;
- an isolated profile can restore an existing private backup and saved selection;
- first launch, owned shutdown, and restart pass without changing the database; and
- failure cleanup preserves unrelated processes and existing settings.

The published runtime remains code/runtime-only. It contains no Butler database,
credentials, provider payloads, logs, Git metadata, or user runtime data.

## Exact-commit release gate

The v0.2.0 release retains the v0.1.1 maintenance baseline:

- JUnit 6.1.3 and its strict dependency-verification metadata; and
- the BF-534 seven-page UX guardrail, now required by the authoritative release
  gate after BF-688 peak-load acceptance.

Before `v0.2.0` may be published, its exact clean main commit must pass
`scripts\butler-release-acceptance.cmd`, a fresh extraction and launch soak, and
the exact-head BF-885/BF-912 Fast Lane journey. Publication must use the source,
runtime, verification-record, and evidence artifacts generated for that same
commit.

Use `butler-setup-restore.cmd` and `butler-setup-launch.cmd -VerifyOnly` from a fresh
extraction for the isolated-profile check, then repeat launch using the saved
selection and verify the dashboard handoff and owned stop. Keep private backup
data and logs outside the release assets. Browser spot-checks of layout and
keyboard navigation supplement the HTTP checks; they do not establish a complete
accessibility audit or a timed human decision task.

This release still requires Java 25 or newer and an existing governed database
or private backup. A league UUID alone cannot recreate missing evidence. Existing
stale roster evidence can block manager-page readiness and needs the governed
recovery workflow. New-league data acquisition is outside this release's scope.

Publication status and downloadable assets are recorded on the GitHub Releases
page; this document defines the release contract rather than claiming publication.

## Release history

`v0.1.1` was published from exact commit
`4eb714bdf07cc1b63910bff7f7e51570e3d38155`, adding JUnit 6.1.3 and the
BF-534 seven-page UX guardrail. The preceding `v0.1.0` stable release used
`6eefc53a75608ca5c97f18a6d0c6c966be783626` after the RC4 fresh-package soak.

`v0.1.0-rc.1` was the first public governed release candidate. Published-package
testing then exposed a portability defect in fully loaded Trade Analyzer and
Decision History routes. BF-789 repaired those routes by using the prebuilt
direct-Java read dispatcher while preserving the fail-closed Gradle shim and
external runtime-data boundary.

RC2 and RC3 hardened that packaged-runtime path. RC4 added the final dependency,
Windows watchdog, PowerShell module-discovery, Gradle-output, and staging
line-ending repairs. The RC4 commit became the exact `v0.1.0` stable commit after
the clean fresh-package soak.

## Publication boundary

Repository release documentation and acceptance commands generate and verify
local artifacts only. They do not create a Git tag, create or modify a GitHub
Release, upload artifacts, alter runtime data, or authorize a Butler or Sleeper
transaction write. Publishing a release remains a separate explicit operation
after the exact-head gates pass.
