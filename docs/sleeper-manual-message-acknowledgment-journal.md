# Sleeper Manual Message Acknowledgment Journal

BF-414 durably records only BF-413 `ACKNOWLEDGED` manual-message evidence.

Journal policy ID:

`sleeper-manual-message-acknowledgment-journal-v1-exact-active-handoff-immutable`

## Purpose

Sleeper does not provide supported official readback proving that a manually sent negotiation message was delivered. BF-413 therefore requires explicit user acknowledgment bound to one exact trusted handoff and payload hash.

BF-414 adds durable audit persistence for that acknowledgment while intentionally leaving the execution attempt `IN_FLIGHT` and the one-shot authorization grant active.

## Required trusted state

A record can be inserted only when the database still contains a matching active manual-message handoff with:

- action `SEND_NEGOTIATION_MESSAGE`,
- destination type `MANAGER`,
- payload kind `NEGOTIATION_MESSAGE_TEXT`,
- reconciliation mode `NO_OFFICIAL_READBACK`,
- matching claim, attempt, grant, handoff, payload SHA-256, destination, and presentation timestamp,
- execution attempt state `IN_FLIGHT`,
- unconsumed authorization grant.

## Persisted provenance

The immutable record stores:

- BF-414 journal policy,
- BF-413 acknowledgment policy,
- BF-402 handoff journal/service provenance,
- claim, attempt, grant, and handoff IDs,
- exact payload SHA-256,
- manager destination ID,
- exact confirmation phrase `SENT_EXACT_MESSAGE`,
- `MANUAL_MESSAGE_SUCCESS` local-completion eligibility,
- first handoff presentation timestamp,
- user acknowledgment timestamp,
- BF-413 evidence reason,
- first durable record timestamp.

## States

- `RECORDED`: exact acknowledgment was persisted.
- `ALREADY_RECORDED`: the identical acknowledgment already exists; the first record is preserved.
- `NOT_ELIGIBLE`: BF-413 did not return an acknowledged success-eligible decision.
- `NOT_FOUND`: trusted message handoff state is unavailable.
- `MISMATCH`: evidence does not match trusted coordinates or conflicts with an existing record.
- `INVALID_STATE`: the execution attempt is no longer `IN_FLIGHT`.
- `GRANT_NOT_ACTIVE`: the one-shot authorization is already consumed.

## Safety boundary

BF-414 is audit persistence only.

Recording acknowledgment does **not**:

- mark the execution `SUCCEEDED`, `FAILED`, or `UNKNOWN`,
- consume the authorization grant,
- send or edit a Sleeper message,
- prove delivery through provider readback,
- infer acknowledgment from handoff presentation alone.

A later local finalizer may consume this exact durable acknowledgment, but it must preserve BF-413/BF-414 provenance and must not convert the user acknowledgment into a claim of provider-verified delivery.
