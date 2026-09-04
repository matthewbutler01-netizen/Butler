# Sleeper Manual Message Finalization CLI

BF-418 exposes the BF-416 local terminalization coordinator as a separate command from BF-417 user acknowledgment.

Command:

`butler trade counter-message-finalize <trusted-grant-id>`

## Input boundary

The command accepts only one trusted persisted authorization grant ID.

It does not accept or override:

- execution claim ID,
- handoff ID,
- payload SHA-256,
- manager destination,
- acknowledgment ID or confirmation,
- acknowledgment timestamp,
- terminal state or grant disposition.

The command resolves the persisted execution attempt, claim, and manual handoff from the grant. BF-416 then loads the durable BF-414 acknowledgment itself and validates all terminalization coordinates from trusted storage.

## Required prior step

BF-417 acknowledgment remains separate.

Without a durable exact `SENT_EXACT_MESSAGE` acknowledgment in the BF-414 journal, BF-418 cannot finalize success and applies no terminal-state or authorization mutation.

## Eligible finalization

When BF-416 finds valid durable acknowledgment evidence for the exact active message handoff, one local SQLite transaction:

1. persists the immutable manual-message terminal outcome,
2. marks the matching attempt `SUCCEEDED`,
3. consumes the matching one-shot authorization grant.

An exact repeated finalization returns the already-applied durable outcome rather than creating a second result.

## Safety boundary

BF-418 is local-only.

It does not:

- send a Sleeper message,
- modify a Sleeper message,
- call a Sleeper write or private endpoint,
- create acknowledgment evidence automatically,
- accept a generic approval as acknowledgment,
- finalize a trade handoff.

The CLI explicitly distinguishes local execution bookkeeping from the external manual message action that the user previously acknowledged through BF-417.
