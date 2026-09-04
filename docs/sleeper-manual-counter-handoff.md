# Sleeper manual counter handoff contract

Service ID: `sleeper-manual-counter-handoff-v1-trusted-claim-present-only`

## Purpose

Sleeper's official public API is read-only, so Butler cannot use a supported API call to send a negotiation message or submit a counter trade.

BF-401 provides a governed manual-handoff artifact from an already-authorized and claimed Butler execution.

## Trust boundary

The service accepts only a persisted BF-393 `claim_id`.

It loads the BF-394 execution request from trusted Butler storage and therefore inherits the exact:

- authorization grant;
- proposal fingerprint;
- action;
- destination;
- payload kind;
- payload bytes;
- SHA-256 payload identity.

Callers cannot supply replacement payload, action, destination, or proposal coordinates to the handoff service.

## `HANDOFF_READY`

A handoff is ready only when the claim resolves to a current `IN_FLIGHT` execution request with an active, unconsumed one-shot authorization.

The artifact presents:

- claim/attempt/grant IDs;
- proposal fingerprint;
- action and destination;
- exact payload and payload SHA-256;
- official Sleeper capability policy provenance;
- reconciliation mode.

`HANDOFF_READY` means only that Butler may show the exact payload for the user to perform manually in Sleeper.

It does **not** mean:

- the user copied it;
- the user opened Sleeper;
- the message was sent;
- the trade was submitted;
- Sleeper accepted or completed anything;
- the authorization was consumed;
- the attempt reached a terminal outcome.

## Reconciliation modes

### Negotiation message

`NO_OFFICIAL_READBACK`

Sleeper's documented public API does not expose a supported direct-message delivery/readback contract. BF-401 therefore makes no delivery assertion.

### Counter trade

`SLEEPER_TRANSACTION_READBACK`

After manual submission, BF-397 through BF-400 may read the official transaction endpoint and compare the observed trade against the governed Butler counter.

## State preservation

BF-401 is read-only.

Preparing or rendering a handoff must leave:

- the execution attempt `IN_FLIGHT`;
- the authorization grant active/unconsumed;
- BF-395/BF-396 outcome state unchanged.

A later contract must govern durable proof that the handoff was actually performed and how supported reconciliation evidence affects the execution lifecycle.
