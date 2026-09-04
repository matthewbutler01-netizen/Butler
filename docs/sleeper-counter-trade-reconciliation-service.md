# Sleeper counter trade reconciliation service

Service ID: `sleeper-counter-trade-reconciliation-service-v1-explicit-round-read-only`

## Purpose

BF-400 composes the supported Sleeper reconciliation layers into one read-only service:

1. BF-399 resolves Butler league/team/player/pick coordinates into Sleeper identifiers and verifies ownership.
2. BF-397 fetches official Sleeper transaction evidence using GET only.
3. BF-398 performs exact governed transaction matching.

The service performs no external write and no Butler execution-state mutation.

## Explicit round requirement

The caller must provide the Sleeper transaction round/week.

BF-400 intentionally does not infer the round from the current NFL state. A guessed round could produce a false `NO_MATCH`, so current-week selection remains a separate contract.

## States

### `RECONCILED`

Identity/ownership resolution succeeded, the official transaction endpoint was fetched, and BF-398 returned a reconciliation result.

The nested BF-398 state remains authoritative (`MATCH_PENDING`, `MATCH_COMPLETE`, `NO_MATCH`, `AMBIGUOUS`, or `INCONCLUSIVE`).

### `INCONCLUSIVE`

The Butler-to-Sleeper expectation could not be resolved.

In this state the service performs zero Sleeper network calls and carries no observed transaction rows.

## Network failures

HTTP failures, malformed responses, or interrupted official API reads are propagated as failures. Butler must not turn an unavailable Sleeper API response into `NO_MATCH`.

## Safety boundary

BF-400 does not:

- submit trades or messages;
- expose a Sleeper write transport;
- use credentials, cookies, or undocumented endpoints;
- infer the transaction round/week;
- mutate BF-395/BF-396 execution outcomes;
- automatically resolve an `UNKNOWN` execution.
