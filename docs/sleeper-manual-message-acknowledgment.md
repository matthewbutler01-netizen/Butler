# Sleeper Manual Message Acknowledgment

BF-413 defines a pure governance contract for one lifecycle gap: Sleeper does not provide supported official readback proving that a negotiation message was sent manually.

The policy does **not** send a message, mutate Butler state, or consume authorization. It only decides whether an explicit user acknowledgment is sufficiently bound to one exact trusted manual-message handoff for a later local finalizer to consider.

Policy ID:

`sleeper-manual-message-acknowledgment-v1-explicit-handoff-payload-confirmation`

## Eligible handoff

Manual acknowledgment is available only when the trusted presented handoff is exactly:

- action `SEND_NEGOTIATION_MESSAGE`,
- destination type `MANAGER`,
- payload kind `NEGOTIATION_MESSAGE_TEXT`,
- reconciliation mode `NO_OFFICIAL_READBACK`.

Trade handoffs and any handoff with official readback are not eligible for this policy.

## Explicit acknowledgment request

The request must bind all of these values:

- trusted grant ID,
- trusted handoff presentation ID,
- exact payload SHA-256,
- exact confirmation phrase `SENT_EXACT_MESSAGE`,
- acknowledgment timestamp.

The grant ID, handoff ID, and payload SHA-256 must exactly match the durable presented handoff.

The acknowledgment timestamp must not precede the first trusted handoff presentation.

## States

### `ACKNOWLEDGED`

Returned only when:

- the handoff is an eligible manual-message handoff,
- all request coordinates exactly match the trusted handoff,
- the acknowledgment time is at or after presentation,
- the confirmation phrase is exactly `SENT_EXACT_MESSAGE`.

This state carries `MANUAL_MESSAGE_SUCCESS` local-completion eligibility and the acknowledged timestamp.

### `NOT_ACKNOWLEDGED`

Returned when the trusted handoff and coordinates match but the confirmation phrase is not exact.

It carries no local-completion eligibility.

### `INCONCLUSIVE`

Returned when:

- the handoff is not an eligible manual-message handoff,
- the grant/handoff/payload coordinates do not match,
- the acknowledgment timestamp predates handoff presentation.

It carries no local-completion eligibility.

## Safety boundary

BF-413 intentionally stops before durable state mutation.

It does not:

- mark an execution attempt `SUCCEEDED`, `FAILED`, or `UNKNOWN`,
- consume an authorization grant,
- send or modify a Sleeper message,
- infer that a message was sent from handoff presentation alone,
- treat a trade handoff as a message handoff,
- accept a generic or unbound confirmation.

A later slice may persist/apply an `ACKNOWLEDGED` decision to Butler's local execution state, but that finalizer must preserve the exact handoff/payload provenance and must remain separate from Sleeper external effects.
