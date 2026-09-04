# Trade Counter Execution Attempt Journal

BF-392 adds the durable execution-attempt journal that sits between read-only BF-391 readiness and any future external side effect.

Journal policy: `trade-counter-execution-attempt-journal-v1-durable-bound-payload-state-machine`

BF-392 performs **no external calls**. It records exactly what a later executor would attempt and provides a fail-closed durable state machine for that attempt.

## Trusted root

Every execution attempt is rooted in one persisted BF-384/BF-387 authorization grant.

Preparation accepts only:

- trusted `grant_id`;
- governed payload kind;
- exact outbound payload text; and
- preparation timestamp.

The repository loads proposal fingerprint, authorized action, destination type, and destination ID from the trusted grant row. Callers cannot supply or override those authorization coordinates.

A consumed grant cannot receive a new execution attempt.

## One grant, one immutable attempt

`grant_id` is unique in `trade_counter_execution_attempts`.

The first preparation creates one attempt ID. Repeating preparation with the exact same payload intent returns the existing attempt. A later attempt with a different payload kind or different payload bytes for the same grant fails closed.

There is no API to replace an attempt or prepare another attempt for the same one-shot grant.

This is deliberately stricter than a generic retry queue. If an execution reaches a terminal or uncertain state, Butler must not create a second attempt under the same authorization.

## Exact outbound payload identity

The journal persists:

- payload kind;
- exact UTF-8 payload text; and
- lowercase SHA-256 hash of those exact bytes.

Payload text is not trimmed or normalized. Whitespace differences change the payload hash and therefore represent a different outbound intent.

Payload kinds are action-bound:

- `SEND_NEGOTIATION_MESSAGE` -> `NEGOTIATION_MESSAGE_TEXT`
- `SUBMIT_COUNTER_TRADE` -> `COUNTER_TRADE_REQUEST_JSON`

BF-392 deliberately treats the trade JSON as opaque exact outbound text. A later governed serializer/executor contract is responsible for defining its canonical schema before real platform execution.

## Durable states

The journal states are:

- `PREPARED`
- `IN_FLIGHT`
- `SUCCEEDED`
- `FAILED`
- `UNKNOWN`

The only legal transitions are:

`PREPARED -> IN_FLIGHT`

then exactly one of:

- `IN_FLIGHT -> SUCCEEDED`
- `IN_FLIGHT -> FAILED`
- `IN_FLIGHT -> UNKNOWN`

`SUCCEEDED`, `FAILED`, and `UNKNOWN` are terminal. There is no transition back to `PREPARED` or `IN_FLIGHT`.

This is especially important for `UNKNOWN`: a timeout or interrupted response may mean the remote platform completed the action. Butler must not blindly retry an `UNKNOWN` attempt.

## Timestamps and outcome detail

The journal records:

- `prepared_at` for every attempt;
- `in_flight_at` only after the attempt is claimed for execution;
- `terminal_at` for terminal states;
- nonblank `outcome_detail` for every terminal state; and
- `updated_at` for the current durable state.

BF-392 does not define the final external platform response schema. `outcome_detail` is an audit field only at this stage.

## Database enforcement

SQLite enforces the trust boundary in addition to Java validation.

A before-insert trigger rejects any attempt unless its copied authorization policy, proposal fingerprint, action, and destination match an existing **active** trusted grant.

A second trigger makes all execution-intent fields immutable after preparation, including:

- attempt ID;
- grant ID;
- authorization policy;
- proposal fingerprint;
- action;
- destination;
- payload kind;
- payload text/hash; and
- preparation timestamp.

A state-transition trigger rejects direct state changes outside the governed state machine.

Table checks also enforce state/timestamp consistency and the action-to-payload-kind mapping.

## Relationship to grant consumption

BF-392 does **not** consume the authorization grant.

It is intentionally possible for a trusted grant to remain unconsumed while its execution attempt is `PREPARED`. A later atomic execution-claim contract must coordinate BF-391 `READY`, the journal state, and authorization consumption semantics before any external call.

BF-392 also does not automatically consume a grant when an attempt becomes `SUCCEEDED`; outcome-to-consumption reconciliation belongs to a later governed slice.

## Safety boundary

BF-392 does not:

- call Sleeper or another fantasy platform;
- send a negotiation message;
- submit a trade;
- consume an authorization grant;
- create a platform retry loop;
- interpret `READY` as permission to execute;
- change Trade Recommendation v5;
- change counter selection/proposal semantics; or
- change the explicit `AUTHORIZE_ONCE` contract.

The next recommended layer is BF-393: atomically claim a `PREPARED` attempt only when the trusted grant is active and the freshly recomputed BF-391 readiness state is `READY`, while still performing no external platform call.
