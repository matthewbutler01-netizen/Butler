# Sleeper manual counter handoff presentation journal

Journal policy ID: `sleeper-manual-counter-handoff-journal-v1-first-presentation-immutable`

## Purpose

BF-402 records the local audit fact that Butler presented one exact BF-401 manual handoff artifact to the user.

This is intentionally weaker than proof that the user acted in Sleeper.

## Durable coordinates

One immutable record is bound one-to-one to the trusted:

- BF-393 claim;
- BF-392 execution attempt;
- one-shot authorization grant;
- proposal fingerprint;
- action and destination;
- payload kind and SHA-256;
- BF-401 reconciliation mode.

The exact payload bytes remain in the immutable BF-392 execution attempt; BF-402 stores the payload hash rather than duplicating the text.

## `presented_at`

The first successful presentation timestamp is durable and immutable.

Repeated presentation of the exact same handoff returns the existing record and preserves the original `presented_at` value.

For manual counter trades, later Sleeper transaction reconciliation should use this first presentation timestamp as the conservative `notBeforeEpochMillis` boundary. This prevents a historical identical trade from being treated as evidence for the newly presented Butler counter.

## Database enforcement

A presentation insert is allowed only when SQLite can prove a matching:

- BF-393 claim;
- `IN_FLIGHT` BF-392 attempt;
- active/unconsumed authorization grant;
- proposal fingerprint;
- action and destination;
- payload kind and SHA-256.

Presentation records are immutable at the database layer.

## What presentation does not prove

A durable handoff record does not prove:

- the user copied the payload;
- the user opened Sleeper;
- the user sent the message;
- the user submitted the trade;
- Sleeper received or accepted anything.

Recording presentation does not terminalize the attempt or consume the grant.

## Reconciliation

For `SUBMIT_COUNTER_TRADE`, BF-397 through BF-400 may later query official Sleeper transaction evidence using `presented_at` as the not-before boundary.

For `SEND_NEGOTIATION_MESSAGE`, Sleeper provides no supported public readback contract, so the presentation record remains presentation evidence only.
