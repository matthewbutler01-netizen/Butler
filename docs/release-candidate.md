# Butler Release Candidate

Current candidate metadata: `v0.1.0-rc.2`.

## Why RC2 exists

`v0.1.0-rc.1` was the first public governed Butler release candidate. Post-publication testing of the published runtime package exposed a portability defect in the fully loaded Trade Lab and Decision History routes. Those routes still entered through Gradle-backed read helpers even though the runtime package intentionally ships without the Gradle toolchain and preserves a fail-closed Gradle shim.

BF-789 repairs that mismatch without weakening the package boundary:

- packaged Trade Lab and Decision History reads use the existing prebuilt `bet-cli` runtime through Butler's governed direct-Java dispatcher;
- the BF-628 decision-history CLI is explicitly allowlisted for direct-Java read execution;
- the runtime Gradle shim remains fail-closed and still authorizes only the exact internal prebuilt `installDist` startup probe;
- no Gradle wrapper or Gradle toolchain is restored to the runtime ZIP;
- governed external `BUTLER_APP_DATA_DIR` behavior is preserved;
- exact POST `/refresh` remains excluded from the packaged read-only acceptance;
- no Butler or Sleeper transaction write is added.

## RC2 release gate

Before RC2 may be published, the exact clean release commit must pass the authoritative Windows release acceptance. In addition to the existing source/runtime packaging, security, missing-database, BF-688, BF-777, BF-778, BF-780, and BF-787 gates, BF-789 must prove the actual extracted runtime can fully render:

- `GET /trade?load=1`
- `GET /history?load=1`

The app must remain healthy after each request. A successful repair includes:

```text
BF-789 PACKAGED COMPANION ROUTES: PASS
```

## Publication boundary

This document prepares release-candidate metadata only. BF-790 does not create a Git tag, create or modify a GitHub Release, upload artifacts, alter runtime data, or authorize stable publication. Publishing `v0.1.0-rc.2` remains a separate explicit approval step.
