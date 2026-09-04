# Sleeper supported execution and reconciliation contract

Policy ID: `sleeper-platform-capability-v1-official-read-only-manual-write-handoff`

## Official capability boundary

Sleeper's documented public API is read-only. It does not require authentication because it does not permit callers to modify league contents.

Official documentation: `https://docs.sleeper.com/`

Butler therefore MUST NOT:

- invent a Sleeper write endpoint;
- reverse-engineer undocumented private/mobile write endpoints;
- send Sleeper session cookies or credentials through an unsupported transport;
- report a message or trade as API-submitted through the official public API.

## Counter actions

### `SEND_NEGOTIATION_MESSAGE`

- official write capability: `UNSUPPORTED_OFFICIAL_API`
- execution channel: `MANUAL_HANDOFF_REQUIRED`
- official read reconciliation: `NOT_AVAILABLE`

Butler may render the governed negotiation text for the user to copy into Sleeper. It cannot verify a direct-message send through the documented public API.

### `SUBMIT_COUNTER_TRADE`

- official write capability: `UNSUPPORTED_OFFICIAL_API`
- execution channel: `MANUAL_HANDOFF_REQUIRED`
- official read reconciliation: `TRANSACTIONS_SUPPORTED`

Butler may render the governed counter proposal for manual submission in Sleeper. After manual submission, Butler may use the documented read-only endpoint:

`GET /v1/league/<league_id>/transactions/<round>`

to retrieve league transaction evidence for later reconciliation.

## GET-only client boundary

`SleeperReadOnlyClient` exposes only a `GetTransport` function. There is intentionally no POST, PUT, PATCH, DELETE, authentication-token, cookie, or credential surface.

The official implementation:

- uses HTTPS;
- sends an HTTP GET request only;
- accepts JSON only;
- uses a bounded request/connect timeout;
- rejects non-200 responses;
- fails closed on malformed transaction JSON.

The transaction model preserves evidence needed by a later reconciliation policy:

- transaction ID;
- type and status;
- creator and timestamps;
- week/leg;
- involved and consenting roster IDs;
- player adds/drops with roster IDs;
- traded draft-pick ownership movement.

## BF-397 non-goals

BF-397 does not:

- determine that a retrieved trade matches Butler's counter proposal;
- resolve Butler fantasy-team IDs to Sleeper roster IDs;
- choose which Sleeper week/round to inspect from live NFL state;
- finalize BF-395/BF-396 UNKNOWN reconciliation;
- contact another manager;
- submit a trade;
- use undocumented Sleeper endpoints.

Those concerns remain separate governed layers.
