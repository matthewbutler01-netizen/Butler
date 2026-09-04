# Trade Counter Authorization

BF-384 defines the first explicit authorization boundary for a fingerprinted Butler counter proposal. BF-385 exposes that contract through a dedicated `trade counter-authorize` CLI without performing any external action. BF-386 adds durable trusted grant storage and atomic single-use consumption state.

Policy: `trade-counter-authorization-v1-explicit-fingerprint-action-destination-once`

CLI:

`butler trade counter-authorize <league-id> <season> <side-a-assets> <side-b-assets> <side-a|side-b> [source] [--minimum-as-of YYYY-MM-DD] -- <message|submit> <destination-id> [--confirm "<exact-confirmation>"]`

The authorization policy and CLI do **not** send a negotiation message, submit a trade, mutate league state, or contact any external platform. BF-386 only persists and consumes authorization state.

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

When `--confirm` is supplied, the complete confirmation phrase must be passed as one quoted command-line argument. The CLI does not trim, normalize, or change case in the confirmation value.

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

## BF-386 durable trusted grant store

`TradeCounterAuthorizationGrantRepository` creates an idempotent SQLite table named `trade_counter_authorization_grants`.

The durable row stores the complete governed grant plus nullable `consumed_at` state. Database constraints independently enforce:

- the BF-384 policy ID;
- valid season range;
- lowercase 64-character proposal fingerprint;
- the two governed action values;
- the two governed destination types;
- `max_uses = 1`;
- message action -> manager destination;
- submit action -> league destination; and
- submit destination ID = proposal league ID.

A partial unique index permits at most one **active, unconsumed** grant for the same proposal fingerprint + action + destination. Once that grant is consumed, a new authorization for the same intent requires a new grant ID and therefore a new explicit authorization event.

The persisted row is the future executor's trusted authorization source. Execution code must load the row by grant ID and must not trust action, destination, fingerprint, or consumption state supplied by a caller.

### Atomic single-use consumption

Grant consumption is one conditional SQLite update:

`UPDATE ... SET consumed_at = ? WHERE grant_id = ? AND consumed_at IS NULL AND max_uses = 1 AND fingerprint/action/destination match`

Exactly one successful update can move a grant from active to consumed. Results are governed as:

- `CONSUMED`: this caller performed the one allowed transition;
- `ALREADY_CONSUMED`: the grant was previously consumed;
- `MISMATCH`: the grant exists and is active, but expected fingerprint/action/destination do not match;
- `NOT_FOUND`: no trusted persisted grant exists for the ID.

A mismatch never consumes the grant.

This is intentionally an **at-most-once** authorization design. A future executor should perform final fresh-evidence revalidation and then atomically consume the trusted grant immediately before attempting the external side effect. If the external platform fails after consumption, Butler must not silently retry under the same grant. A retry requires a newly reviewed proposal if necessary and a new explicit authorization. This avoids duplicate messages or duplicate trade submissions when the external platform's idempotency guarantees are unknown.

BF-386 does not claim end-to-end exactly-once delivery. Exactly-once external execution would require a platform-supported idempotency key or a transactional integration unavailable at this layer.

## Fresh-evidence revalidation

Before any future execution, the full proposal pipeline must be re-run using current evidence and must produce a fresh BF-382 proposal identity.

`TradeCounterAuthorizationPolicy.revalidate(...)` compares that newly produced identity with the trusted persisted grant.

Results:

- `MATCH`: fingerprint and all governed coordinates still match;
- `DRIFTED`: the proposal changed, disappeared, or no longer has an identified counter;
- `INCONCLUSIVE`: the fresh proposal identity is inconclusive.

Only `MATCH` is eligible to proceed to a future execution gate.

The policy cannot prove by itself that the supplied identity was freshly recomputed. The future executor is responsible for running the live evidence pipeline immediately before revalidation rather than replaying an old identity artifact.

## Safety boundary

BF-384 through BF-386 do not:

- treat blanket project approval as trade authorization;
- authorize more than one action or destination;
- authorize an unidentified or inconclusive proposal;
- send the BF-378 negotiation message;
- submit the BF-380 revised packages;
- infer a manager recipient;
- call an external fantasy platform;
- change Trade Recommendation v5; or
- change BF-369 through BF-383 decision semantics.

BF-386 adds persistence and single-use state only. It does not wire BF-385 to persistence and it does not expose a consume command.

A later execution capability must independently enforce trusted grant loading, fresh proposal revalidation, exact action/destination binding, and atomic single-use consumption before any external side effect is possible.
