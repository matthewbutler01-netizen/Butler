# Butler Release Status

## Current stable release

The current public stable release is `v0.1.0`, published from exact commit
`6eefc53a75608ca5c97f18a6d0c6c966be783626` after the RC4 fresh-package
soak completed successfully.

The stable package proved that:

- its downloaded runtime ZIP matched the published SHA-256 sidecar;
- the extracted prebuilt runtime launched without a Gradle wrapper or toolchain;
- BF-768 release-security checks passed;
- BF-773 packaged-runtime launch acceptance passed; and
- all seven manager pages returned HTTP 200 against governed external runtime data.

The published runtime remains code/runtime-only. It contains no Butler database,
credentials, provider payloads, logs, Git metadata, or user runtime data.

## Next maintenance release

The next planned maintenance release is `v0.1.1`. Main has advanced beyond
`v0.1.0` with two release-maintenance changes:

- JUnit 6.1.3 and its strict dependency-verification metadata; and
- the BF-534 seven-page UX guardrail, now required by the authoritative release
  gate after BF-688 peak-load acceptance.

Before `v0.1.1` may be published, its exact clean main commit must pass
`scripts\butler-release-acceptance.cmd`, a fresh extraction and launch soak, and
the exact-head BF-885/BF-912 Fast Lane journey. Publication must use the source,
runtime, verification-record, and evidence artifacts generated for that same
commit.

## Release history

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
