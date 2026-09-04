# Trade Counter Atomic Execution Claim

BF-393 adds the durable claim gate between BF-391 read-only readiness and any future external executor.

Claim policy: `trade-counter-execution-claim-v1-ready-active-prepared-atomic`

BF-393 still performs **no external platform call** and does **not consume the authorization grant**.

## Required inputs

A claim request contains only:

- BF-392 execution attempt ID;
- governed BF-391 execution-readiness result; and
- claim timestamp.

The readiness result must be exactly `READY`. `DRIFTED`, `INCONCLUSIVE`, consumed, or replay-blocked readiness cannot create a claim.

The repository does not accept caller-supplied action, destination, proposal fingerprint, or payload. Those are loaded from the prepared attempt and compared against the trusted readiness artifact.

## Atomic claim transaction

A successful claim transaction requires all of the following at commit time:

1. the execution attempt exists;
2. the attempt is still `PREPARED`;
3. the BF-391 readiness artifact is `READY`;
4. readiness grant ID, authorized/fresh fingerprint, action, and destination match the prepared attempt;
5. the persisted authorization grant still exists and remains unconsumed;
6. the persisted grant coordinates still match the attempt; and
7. no claim already exists for the attempt or grant.

Inside one SQLite transaction Butler:

1. inserts one immutable execution-claim row; then
2. conditionally moves the matching BF-392 attempt from `PREPARED` to `IN_FLIGHT`.

If the second operation does not succeed, the transaction is rolled back, so a durable claim cannot be committed without its corresponding `IN_FLIGHT` attempt.

## Claim identity

The durable claim records:

- claim ID;
- claim policy ID;
- attempt ID;
- grant ID;
- BF-391 readiness policy ID;
- authorization policy ID;
- authorized proposal fingerprint;
- fresh proposal fingerprint;
- trusted action;
- trusted destination; and
- claim timestamp.

For a valid claim the authorized and fresh fingerprints must be identical.

Each attempt and each grant may have at most one claim.

Repeated claim calls with the same READY evidence return the existing claim as `ALREADY_CLAIMED`; they do not create another claim or another transition.

## Claim result states

- `CLAIMED` — durable claim created and attempt atomically moved to `IN_FLIGHT`.
- `ALREADY_CLAIMED` — the same durable claim already exists.
- `READINESS_NOT_READY` — BF-391 evidence was not `READY`; storage is untouched.
- `ATTEMPT_NOT_FOUND` — no prepared attempt exists for the supplied attempt ID.
- `ATTEMPT_NOT_PREPARED` — the attempt is already in another state and cannot be newly claimed.
- `GRANT_NOT_ACTIVE` — trusted authorization was consumed after readiness evaluation but before claim.
- `MISMATCH` — readiness/attempt/claim coordinates disagree.

All non-claim states fail closed.

## Database-level bypass protection

BF-392 originally supplied the generic `PREPARED -> IN_FLIGHT` journal transition primitive.

Once the BF-393 claim schema is initialized, an additional SQLite trigger requires a matching row in `trade_counter_execution_claims` before any `PREPARED -> IN_FLIGHT` update can succeed.

This means the database—not merely future CLI convention—enforces the READY claim boundary. A caller that directly invokes the older journal transition after BF-393 initialization receives a database failure unless the durable claim exists.

The claim row itself is immutable after insertion.

## Readiness-to-claim race protection

BF-391 readiness is intentionally read-only and can become stale immediately after it is calculated.

BF-393 therefore rechecks the authoritative stored grant during the claim transaction. If the grant was consumed after the READY result was produced, the claim returns `GRANT_NOT_ACTIVE` and the attempt remains `PREPARED`.

Market or roster evidence can still change after the claim. A later executor contract must define the final proximity between claim and external call; BF-393 does not pretend that a historical READY result remains current forever.

## Relationship to authorization consumption

A successful BF-393 claim moves the attempt to `IN_FLIGHT` but deliberately leaves the trusted authorization grant **unconsumed**.

This is not permission to retry blindly. `IN_FLIGHT` means Butler has reserved one execution attempt and no competing caller may start another under that grant.

Later outcome reconciliation must decide when a confirmed external success consumes the one-shot authorization, and how `FAILED` or `UNKNOWN` outcomes are handled. BF-393 does not make that policy decision.

## Safety boundary

BF-393 does not:

- send a negotiation message;
- submit a counter trade;
- call Sleeper or another fantasy platform;
- consume an authorization grant;
- mark an execution attempt `SUCCEEDED`, `FAILED`, or `UNKNOWN`;
- retry an execution;
- change Trade Recommendation v5;
- change counter selection/proposal semantics; or
- change the explicit `AUTHORIZE_ONCE` contract.

The next recommended slice is BF-394: define a governed executor interface and a dry-run/fake executor that accepts only a durable BF-393 claim plus its immutable BF-392 payload. No real platform adapter should be introduced until that interface and its outcome contract are tested.
