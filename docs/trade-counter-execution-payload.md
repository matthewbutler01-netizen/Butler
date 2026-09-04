# Trade Counter Execution Payload

BF-403 governs the exact payload bytes that may enter the durable BF-392 execution-attempt journal.

Policy: `trade-counter-execution-payload-v1-fresh-authorized-governed-artifacts`

This policy is intentionally between authorization/readiness and execution-attempt preparation. It prevents an authorized counter fingerprint from being paired with caller-supplied arbitrary message text or trade JSON.

## Required inputs

Payload materialization requires all of the following from the same fresh governed counter:

- the trusted BF-384 authorization grant;
- the fresh BF-382 proposal identity;
- the BF-380 complete materialized counter packages;
- the BF-378 governed negotiation message.

The authorization grant is revalidated against the fresh proposal identity before any payload is emitted. Drift returns `NOT_AVAILABLE`; inconclusive fresh identity returns `INCONCLUSIVE`.

The fresh identity, materialized packages, and negotiation message must have exactly matching league, season, value source, minimum-as-of boundary, and perspective coordinates.

## Message payload

For `SEND_NEGOTIATION_MESSAGE`, the payload is exactly the BF-378 governed neutral negotiation text and the journal payload kind is `NEGOTIATION_MESSAGE_TEXT`.

The policy does not accept caller-supplied alternate wording.

## Trade payload

For `SUBMIT_COUNTER_TRADE`, the payload kind is `COUNTER_TRADE_REQUEST_JSON` and the payload uses the deterministic Butler schema:

`butler-counter-trade-request-v1`

The JSON contains:

- proposal fingerprint;
- league ID and destination league ID;
- season;
- value source and minimum-as-of boundary;
- explicit team perspective;
- complete revised Side A player/pick IDs;
- complete revised Side B player/pick IDs.

This JSON is a **Butler execution/manual-handoff contract**. It is not a Sleeper HTTP request body and does not represent a private or undocumented Sleeper write API.

## States

- `PAYLOAD_AVAILABLE`
- `NOT_AVAILABLE`
- `INCONCLUSIVE`

A payload is available only after exact fresh authorization revalidation and governed artifact consistency checks.

## Safety boundary

BF-403 does not:

- create or consume authorization grants;
- prepare or claim an execution attempt;
- mark an attempt in flight or terminal;
- send a manager message;
- submit a trade;
- call Sleeper or another external platform;
- infer a recipient or league destination;
- accept arbitrary payload bytes.

A future orchestration slice may feed the BF-403 payload directly into BF-392 preparation, then BF-393 claim and the governed manual Sleeper handoff path.
