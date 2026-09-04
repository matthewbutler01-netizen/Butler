# Trade Counter Execution Readiness

BF-390 defines a read-only execution-readiness policy for a trusted persisted counter authorization.

Policy: `trade-counter-execution-readiness-v1-trusted-grant-fresh-replay-no-consume`

This policy answers one question only: **does the currently recomputed governed proposal still match the exact proposal that the user explicitly authorized?**

It does not consume the authorization grant and does not perform any external action.

## Inputs

The policy receives:

- the trusted persisted BF-384/BF-387 `AuthorizationGrant`;
- whether that grant is already consumed;
- whether immutable BF-388/BF-389 replay context is available; and
- when replay is eligible, a freshly recomputed BF-382 proposal identity.

The fresh identity is expected to come from rerunning the full governed counter-proposal pipeline using the trusted grant coordinates and immutable original Side A / Side B packages.

## Readiness states

`READY`

- trusted grant is active;
- replay context is available; and
- `TradeCounterAuthorizationPolicy.revalidate(...)` returns `MATCH`.

`DRIFTED`

- trusted grant is active;
- replay context is available; and
- fresh proposal identity no longer exactly matches the authorized fingerprint and governed coordinates, including the case where the fresh pipeline no longer produces a COUNTER identity.

`INCONCLUSIVE`

- trusted grant is active;
- replay context is available; and
- fresh governed proposal identity is inconclusive.

`BLOCKED_ALREADY_CONSUMED`

- the trusted grant was already consumed.
- no fresh proposal identity is evaluated for this state.

`BLOCKED_MISSING_REPLAY_CONTEXT`

- the trusted grant is active but has no immutable original trade replay context.
- no fresh proposal identity is evaluated for this state.

## Evaluation order

The gate is intentionally fail-closed and ordered:

1. consumed grant -> `BLOCKED_ALREADY_CONSUMED`;
2. missing replay context -> `BLOCKED_MISSING_REPLAY_CONTEXT`;
3. otherwise a fresh proposal identity is mandatory;
4. fresh revalidation maps to `READY`, `DRIFTED`, or `INCONCLUSIVE`.

A caller must not supply a fresh identity for a consumed or replay-missing grant. This prevents a later integration from masking a terminal authorization-state problem with recomputed proposal evidence.

## Exact matching

`READY` requires the existing BF-384 revalidation result `MATCH`.

That comparison includes:

- league ID;
- season;
- value source;
- minimum-as-of coordinate;
- team perspective; and
- BF-382 proposal fingerprint.

A matching fingerprint with different governed coordinates is still `DRIFTED`.

The readiness result retains the trusted action and destination so downstream code does not need to accept them from untrusted caller input.

## No-consume boundary

BF-390 does **not** call `TradeCounterAuthorizationGrantRepository.consume(...)`.

A `READY` result means only that the trusted authorization still matches fresh governed evidence at the instant of evaluation. It is not proof that the grant will remain ready later, and it is not permission to retry or execute without a later atomic consumption gate.

## Safety boundary

BF-390 does not:

- load a grant from storage;
- load replay context from storage;
- rerun the live recommendation/counter pipeline itself;
- consume a grant;
- send a negotiation message;
- submit a trade;
- call an external fantasy platform;
- change the explicit `AUTHORIZE_ONCE` contract;
- change Trade Recommendation v5; or
- change counter candidate/proposal semantics.

The next safe layer is a read-only CLI that loads the trusted grant and replay context by grant ID, reruns the existing live proposal pipeline, passes the fresh identity into this policy, and prints the readiness state. Grant consumption remains outside that read-only surface.
