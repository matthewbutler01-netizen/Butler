# Trade Counter Authorization Replay Context

BF-388 adds the immutable proposal coordinates required to rerun a persisted counter authorization from current evidence before any future execution.

A BF-387 trusted grant already stores the proposal fingerprint, league/season/source/minimum-as-of coordinates, perspective, action, and destination. That is sufficient to identify what was authorized, but it is not sufficient to reconstruct the original trade packages that produced the fingerprint.

BF-388 therefore persists the original Side A and Side B asset identities alongside the trusted grant.

## Stored evidence

`TradeCounterAuthorizationReplayContextRepository` stores only original trade asset identities:

- Side A player IDs, in package order;
- Side A draft-pick IDs, in package order;
- Side B player IDs, in package order; and
- Side B draft-pick IDs, in package order.

Display names, market values, recommendation output, and derived strategic evidence are deliberately not persisted as replay authority. Those values must be recomputed from current evidence.

## Grant binding

Replay context is keyed by the trusted authorization `grant_id` and has a foreign-key relationship to `trade_counter_authorization_grants`.

Context may be attached only when:

- the trusted grant exists;
- the grant is still unconsumed;
- each original trade side contains at least one asset;
- asset IDs are nonblank and unique within each asset type; and
- the same player or draft pick does not appear on both sides.

A missing or already-consumed grant fails closed.

## Immutability

Replay context is one-time attached.

- Reattaching the exact same Side A / Side B packages is idempotent and returns `ALREADY_ATTACHED`.
- Attempting to attach different packages to a grant that already has replay context fails closed.
- There is no update or delete API for replay coordinates.

This prevents a persisted authorization grant from being silently rebound to a different trade after the user reviewed and authorized it.

## Storage model

The normalized SQLite table `trade_counter_authorization_replay_assets` records:

- `grant_id`
- `side` (`SIDE_A` or `SIDE_B`)
- `asset_type` (`PLAYER` or `DRAFT_PICK`)
- zero-based `ordinal`
- `asset_id`

Primary/unique constraints preserve one ordered identity per grant/side/type and reject duplicate asset rows.

The repository writes the complete replay context inside one transaction. A partial package is never intentionally committed.

## Future fresh-evidence replay

A later execution-readiness gate can load:

1. the trusted persisted BF-387 grant by grant ID; and
2. the BF-388 original Side A / Side B packages.

It can then rerun the existing governed counter-proposal pipeline using the stored league, season, source, minimum-as-of, perspective, and original packages. The resulting fresh BF-382 proposal identity must pass `TradeCounterAuthorizationPolicy.revalidate(...)` with `MATCH` before consumption can even be considered.

The stored replay context does not substitute for current market, strategic, positional, flexible-pressure, or other recommendation evidence.

## Safety boundary

BF-388 does not:

- consume an authorization grant;
- mark an action executable;
- send a negotiation message;
- submit a trade;
- call an external fantasy platform;
- change the authorization confirmation contract;
- change Trade Recommendation v5; or
- change counter selection/proposal semantics.

The next safe layer is a read-only fresh-evidence execution-readiness check that loads the trusted grant and immutable replay context, reruns the live proposal pipeline, and reports `MATCH`, `DRIFTED`, or `INCONCLUSIVE`. Grant consumption remains a later gate.
