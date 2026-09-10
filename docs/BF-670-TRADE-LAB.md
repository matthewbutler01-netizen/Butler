# BF-670 Trade Lab

BF-670 adds the first read-only governed Trade Lab to the local Butler app.

## Architecture

- `scripts/butler-app-shell.ps1` is the small public loopback router.
- `scripts/butler-app-shell-core.ps1` preserves the pre-BF-670 BF-667/BF-668 app shell byte-for-byte.
- `scripts/butler-trade-lab-host.ps1` contains read-only host/presentation helpers.
- `scripts/butler-trade-lab.ps1` owns Trade Lab request parsing, exact persisted-ownership validation, governed evaluation parsing, and HTML rendering.
- Java analyzers and `ButlerCommandRouter` remain authoritative.

## Governed evaluation

Trade Lab always treats the exact BF-623/BF-610 bound user team as Side A and the selected league opponent as Side B. Selected assets must be exact tokens from the current persisted `league assets` inventory. The only trade recommendation command invoked by the page is:

```text
trade recommendation <league-id> <season> <side-a-assets> <side-b-assets> side-a
```

That routed command currently resolves to Butler's v5 trade recommendation implementation.

## Safety boundary

Trade Lab is GET-only and read-only. BF-670 does not expose trade counter-proposal, authorization, handoff, finalize, message, Sleeper transaction, roster mutation, evidence refresh, FAAB, or waiver write paths. It introduces no new trade score, valuation, market-edge, posture, positional-pressure, flexible-pressure, veto, or recommendation methodology.

## Live acceptance

After CI passes and the PR is merged, start Butler from current `main`, open `Trade Lab`, select one league opponent, select at least one exact outgoing and incoming asset, and evaluate. A governed recommendation or explicit governed inconclusive result both satisfy the evaluation path; missing evidence must never be manufactured.