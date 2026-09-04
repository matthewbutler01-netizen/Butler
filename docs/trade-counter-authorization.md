# Trade Counter Authorization

BF-384 defines the first explicit authorization boundary for a fingerprinted Butler counter proposal.

Policy: `trade-counter-authorization-v1-explicit-fingerprint-action-destination-once`

This policy creates and validates authorization artifacts only. It does **not** send a negotiation message, submit a trade, mutate league state, or contact any external platform.

## Preconditions

Authorization can be requested only for a BF-382 proposal identity in state `IDENTIFIED` with a 64-character lowercase SHA-256 fingerprint.

`NO_IDENTITY` and `INCONCLUSIVE` proposals cannot enter authorization.

## Authorized actions

BF-384 recognizes two future execution intents:

- `SEND_NEGOTIATION_MESSAGE`
- `SUBMIT_COUNTER_TRADE`

They are separate permissions. Authorizing one does not authorize the other.

## Destination binding

Authorization is bound to one stable destination identifier.

- `SEND_NEGOTIATION_MESSAGE` requires destination type `MANAGER`.
- `SUBMIT_COUNTER_TRADE` requires destination type `LEAGUE`, and the destination ID must exactly equal the proposal league ID.

The policy does not infer a manager destination. A future surface must supply and validate the intended manager's stable platform identifier before requesting authorization.

## Exact confirmation phrase

The policy generates one canonical confirmation string:

`AUTHORIZE_ONCE action=<ACTION> proposal=<SHA-256> destination=<TYPE>:<ID>`

Authorization succeeds only when the supplied confirmation text exactly equals that string.

Generic or standing phrases such as `approved`, `go`, `continue`, `keep approving recommended`, or similar blanket approvals are not valid authorization for a specific counter proposal.

The public `AuthorizationRequest` constructor independently verifies the canonical phrase, so callers cannot weaken the contract by manufacturing a request whose confirmation text is easier to satisfy.

## Single-use grant

A successful decision creates an `AuthorizationGrant` containing:

- a unique UUID grant ID;
- grant timestamp;
- league/season/source/minimum-as-of coordinates;
- explicit team perspective;
- proposal fingerprint;
- exact action;
- exact destination; and
- `maxUses = 1`.

The grant is a governed intent artifact. BF-384 itself does not persist or consume grants, so it does not claim to enforce atomic one-time use by itself. A future execution layer must persist the grant and atomically mark it consumed before or as the external side effect is committed.

## Fresh-evidence revalidation

Before any future execution, the full proposal pipeline must be re-run using current evidence and must produce a fresh BF-382 proposal identity.

`TradeCounterAuthorizationPolicy.revalidate(...)` compares that newly produced identity with the grant.

Results:

- `MATCH`: fingerprint and all governed coordinates still match;
- `DRIFTED`: the proposal changed, disappeared, or no longer has an identified counter;
- `INCONCLUSIVE`: the fresh proposal identity is inconclusive.

Only `MATCH` is eligible to proceed to a future execution gate.

The policy cannot prove by itself that the supplied identity was freshly recomputed. The future executor is responsible for running the live evidence pipeline immediately before revalidation rather than replaying an old identity artifact.

## Safety boundary

BF-384 does not:

- treat blanket project approval as trade authorization;
- authorize more than one action or destination;
- authorize an unidentified or inconclusive proposal;
- send the BF-378 negotiation message;
- submit the BF-380 revised packages;
- choose or infer a manager recipient;
- persist or consume authorization grants;
- call an external fantasy platform;
- change Trade Recommendation v5; or
- change BF-369 through BF-383 decision semantics.

A later CLI/UI may display the required confirmation phrase and produce a grant after the user enters that exact phrase. A still-later execution capability must independently enforce fresh revalidation, single-use consumption, destination validation, and the exact authorized action before performing any external side effect.
