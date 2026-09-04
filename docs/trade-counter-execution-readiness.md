# Trade Counter Execution Readiness

BF-390 defines a read-only execution-readiness policy for a trusted persisted counter authorization. BF-391 exposes that policy through a trusted-grant-only CLI that reruns the governed counter proposal from current evidence.

Policy: `trade-counter-execution-readiness-v1-trusted-grant-fresh-replay-no-consume`

CLI:

`butler trade counter-readiness <trusted-grant-id>`

This capability answers one question only: **does the currently recomputed governed proposal still match the exact proposal that the user explicitly authorized?**

It does not consume the authorization grant and does not perform any external action.

## Trusted inputs

The BF-391 CLI accepts only a trusted grant ID. It does not accept league, season, package, perspective, action, destination, source, or minimum-as-of values from the caller.

It loads those coordinates from Butler's persisted authorization artifacts:

- BF-386/BF-387 trusted `AuthorizationGrant` row;
- BF-388/BF-389 immutable original Side A / Side B replay packages.

The original trade packages and all governed coordinates are reconstructed into the existing `ButlerTradeCounterDecisionCli.Options` model from storage.

The readiness CLI uses the same package-private identity builder used by `trade counter-authorize`, so fresh revalidation reruns the same v5 recommendation, strategic eligibility, candidate selection, counter proposal, envelope, materialization, and BF-382 identity pipeline. The visibility change is package-only and does not alter authorization behavior.

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

- the trusted grant was already consumed;
- the CLI does not load/replay current proposal evidence for this terminal state.

`BLOCKED_MISSING_REPLAY_CONTEXT`

- the trusted grant is active but has no immutable original trade replay context;
- the CLI does not run the live proposal pipeline for this terminal state.

A missing trusted grant is reported as readiness unavailable before the BF-390 policy can be evaluated.

## Evaluation order

The gate is intentionally fail-closed and ordered:

1. load the trusted grant by ID;
2. missing grant -> readiness unavailable;
3. consumed grant -> `BLOCKED_ALREADY_CONSUMED`;
4. missing replay context -> `BLOCKED_MISSING_REPLAY_CONTEXT`;
5. reconstruct live proposal coordinates only from the trusted stored grant + replay context;
6. rerun the governed proposal identity pipeline;
7. fresh revalidation maps to `READY`, `DRIFTED`, or `INCONCLUSIVE`.

No caller-supplied trade coordinates can override persisted authorization coordinates.

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

The readiness result retains the trusted action and destination from the persisted grant rather than accepting them as command arguments.

## No-consume boundary

BF-390/BF-391 do **not** call `TradeCounterAuthorizationGrantRepository.consume(...)`.

A `READY` result means only that the trusted authorization still matches fresh governed evidence at the instant of evaluation. It is not proof that the grant will remain ready later, and it is not permission to retry or execute without a later atomic consumption gate.

CLI output explicitly states:

- readiness never consumes the authorization grant; and
- `READY` is evidence status only; no message is sent and no trade is submitted.

## Safety boundary

BF-390/BF-391 do not:

- consume a grant;
- mutate a persisted authorization or replay context;
- accept caller-supplied action/destination/trade coordinates for revalidation;
- send a negotiation message;
- submit a trade;
- call an external fantasy platform;
- change the explicit `AUTHORIZE_ONCE` contract;
- change Trade Recommendation v5; or
- change counter candidate/proposal semantics.

BF-391 completes the safe read-only execution-readiness chain. The next step would cross into atomic authorization consumption and platform-specific external side effects. That requires a separate execution-order/idempotency decision and is not implied by a `READY` result or by standing project approval.
