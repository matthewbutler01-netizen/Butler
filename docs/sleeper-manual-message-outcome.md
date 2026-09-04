# Sleeper Manual Message Outcome Finalization

BF-416 closes the local execution lifecycle for a manually sent Sleeper negotiation message only after Butler already has durable BF-414 acknowledgment evidence.

## Input boundary

The coordinator accepts only a trusted execution claim ID and the local application time.

It does not accept or override:

- grant ID,
- handoff ID,
- proposal fingerprint,
- payload text or payload SHA-256,
- manager destination,
- acknowledgment confirmation,
- acknowledgment timestamp.

All completion coordinates are loaded from the immutable BF-414 acknowledgment journal and its trusted BF-402 handoff chain.

## Required evidence

A terminal success requires an existing durable acknowledgment that is bound to:

- BF-413 acknowledgment policy,
- `SENT_EXACT_MESSAGE`,
- `MANUAL_MESSAGE_SUCCESS`,
- one exact message handoff,
- one exact payload SHA-256,
- one manager destination,
- the matching execution attempt and one-shot authorization grant.

The attempt must still be `IN_FLIGHT` and the grant must still be active.

## Atomic state transition

For eligible trusted state, one SQLite transaction:

1. inserts an immutable `sleeper_manual_message_terminal_outcomes` row,
2. marks the matching execution attempt `SUCCEEDED`,
3. consumes the matching one-shot authorization grant,
4. commits all three changes together.

A repeated exact finalization resolves to the original durable outcome and does not create another terminal result.

## Shared database guards

BF-416 extends the BF-415 shared installer so its terminal and grant-consumption triggers dynamically retain every supported manual outcome table currently present.

This prevents initialization order from downgrading protection when both:

- exact Sleeper trade readback outcomes, and
- explicit manual-message acknowledgment outcomes

exist in the same database.

A BF-414 acknowledgment row by itself does **not** authorize direct SQL terminalization or grant consumption. The BF-416 durable terminal outcome must exist first.

## Safety boundary

BF-416 is local-only.

It does not:

- send a Sleeper message,
- call a Sleeper write or private endpoint,
- infer that presenting a handoff means the message was sent,
- accept generic acknowledgment text,
- finalize a trade handoff,
- retry an external action.

The user acknowledgment remains the evidence source because Sleeper does not expose a governed official readback for this manual negotiation-message action.
