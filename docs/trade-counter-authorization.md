# Trade Counter Authorization

BF-384 defines the first explicit authorization boundary for a fingerprinted Butler counter proposal. BF-385 exposes that contract through a dedicated `trade counter-authorize` CLI without performing any external action.

Policy: `trade-counter-authorization-v1-explicit-fingerprint-action-destination-once`

CLI:

`butler trade counter-authorize <league-id> <season> <side-a-assets> <side-b-assets> <side-a|side-b> [source] [--minimum-as-of YYYY-MM-DD] -- <message|submit> <destination-id> [--confirm "<exact-confirmation>"]`

The policy and CLI create and validate authorization artifacts only. They do **not** send a negotiation message, submit a trade, mutate league state, or contact any external platform.

## Preconditions

Authorization can be requested only for a BF-382 proposal identity in state `IDENTIFIED` with a 64-character lowercase SHA-256 fingerprint.

`NO_IDENTITY` and `INCONCLUSIVE` proposals cannot enter authorization.

BF-385 reruns the same live recommendation, strategic-eligibility, proposal, materialization, and identity policies used by the governed counter-proposal path before it creates an authorization request.

## Authorized actions

BF-384 recognizes two future execution intents:

- `SEND_NEGOTIATION_MESSAGE`
- `SUBMIT_COUNTER_TRADE`

They are separate permissions. Authorizing one does not authorize the other.

The BF-385 CLI maps:

- `message` -> `SEND_NEGOTIATION_MESSAGE`
- `submit` -> `SUBMIT_COUNTER_TRADE`

## Destination binding

Authorization is bound to one stable destination identifier.

- `SEND_NEGOTIATION_MESSAGE` requires destination type `MANAGER`.
- `SUBMIT_COUNTER_TRADE` requires destination type `LEAGUE`, and the destination ID must exactly equal the proposal league ID.

The policy does not infer a manager destination. The CLI requires the manager destination ID to be supplied explicitly.

The `--` separator is mandatory. Everything before it is parsed using the existing governed counter-proposal trade-coordinate parser; everything after it is authorization-only input. This prevents source or minimum-as-of options from being confused with authorization action or destination values.

## Exact confirmation phrase

The policy generates one canonical confirmation string:

`AUTHORIZE_ONCE action=<ACTION> proposal=<SHA-256> destination=<TYPE>:<ID>`

Authorization succeeds only when the supplied confirmation text exactly equals that string.

Generic or standing phrases such as `approved`, `go`, `continue`, `keep approving recommended`, or similar blanket approvals are not valid authorization for a specific counter proposal.

The public `AuthorizationRequest` constructor independently verifies the canonical phrase, so callers cannot weaken the contract by manufacturing a request whose confirmation text is easier to satisfy.

### CLI request mode

When `--confirm` is omitted, BF-385 prints:

- the exact proposal fingerprint;
- requested action;
- exact destination;
- `maxUses = 1`; and
- the exact `AUTHORIZE_ONCE ...` phrase that would be required.

It creates no grant.

### CLI confirmation mode

When `--confirm` is supplied, the complete confirmation phrase must be passed as one quoted command-line argument.

If it matches exactly, BF-385 can create an in-memory `AuthorizationGrant` artifact. If it differs at all, authorization is rejected and no grant is created.

Even an `AUTHORIZED` CLI result performs no external action.

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

The grant is a governed intent artifact. BF-384/BF-385 do not persist or consume grants, so they do not claim to enforce atomic one-time use by themselves. A future execution layer must persist the grant and atomically mark it consumed before or as the external side effect is committed.

A future executor must not accept arbitrary client-supplied grant fields as authority. It must load the persisted trusted grant by grant ID, verify the stored action/destination/fingerprint, revalidate current evidence, and atomically enforce the one-use limit.

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

BF-384/BF-385 do not:

- treat blanket project approval as trade authorization;
- authorize more than one action or destination;
- authorize an unidentified or inconclusive proposal;
- send the BF-378 negotiation message;
- submit the BF-380 revised packages;
- infer a manager recipient;
- persist or consume authorization grants;
- call an external fantasy platform;
- change Trade Recommendation v5; or
- change BF-369 through BF-383 decision semantics.

The next execution capability is a separate product and safety boundary. It must independently enforce trusted grant persistence, fresh proposal revalidation, exact action/destination binding, and atomic single-use consumption before any external side effect is possible.
