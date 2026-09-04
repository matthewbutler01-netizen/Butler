# Sleeper Manual Message Acknowledgment CLI

BF-417 exposes explicit user acknowledgment for a manually sent Sleeper negotiation message without conflating acknowledgment with execution finalization.

Command:

`butler trade counter-message-ack <trusted-grant-id> [--confirm SENT_EXACT_MESSAGE]`

## Preview mode

Without `--confirm`, Butler loads only the trusted persisted execution attempt, claim, and manual handoff for the supplied grant ID and prints:

- trusted grant ID,
- execution claim ID,
- handoff presentation ID,
- authorized action and destination,
- payload SHA-256,
- first presentation time,
- required exact confirmation phrase.

Preview mode records nothing.

## Exact confirmation

The only accepted confirmation is the raw, case-sensitive string:

`SENT_EXACT_MESSAGE`

The CLI checks the raw argument before constructing BF-413 evidence. Whitespace or case variants are rejected, including values that would otherwise normalize to the same text.

When the exact confirmation is supplied, Butler:

1. binds the acknowledgment to the trusted handoff/grant/payload coordinates,
2. applies BF-413 acknowledgment policy,
3. persists eligible evidence through the immutable BF-414 journal.

The user does not supply claim ID, handoff ID, payload hash, destination, or acknowledgment policy coordinates.

## Safety boundary

BF-417 does not:

- send a Sleeper message,
- call a Sleeper write/private endpoint,
- mark execution `SUCCEEDED`,
- consume authorization,
- finalize a trade,
- infer delivery from handoff presentation.

A recorded acknowledgment means only that the user explicitly stated that the exact governed message payload was sent. BF-416 remains the separate local terminalization mechanism.
