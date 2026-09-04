# Trade Counter Authorization

BF-384 defines the explicit authorization contract for a fingerprinted Butler counter proposal. BF-385 exposes that contract through `trade counter-authorize`. BF-386 adds durable trusted grant storage and atomic single-use consumption state. BF-387 wires only successful exact-confirmation CLI authorizations into that durable store.

Policy: `trade-counter-authorization-v1-explicit-fingerprint-action-destination-once`

CLI:

`butler trade counter-authorize <league-id> <season> <side-a-assets> <side-b-assets> <side-a|side-b> [source] [--minimum-as-of YYYY-MM-DD] -- <message|submit> <destination-id> [--confirm "<exact-confirmation>"]`

The authorization path does **not** send a negotiation message, submit a trade, mutate league state, or contact an external platform.

## Preconditions

Authorization can be requested only for a BF-382 proposal identity in state `IDENTIFIED` with a 64-character lowercase SHA-256 fingerprint.

`NO_IDENTITY` and `INCONCLUSIVE` proposals cannot enter authorization.

The CLI reruns the governed recommendation, strategic-eligibility, proposal, materialization, and identity pipeline before creating an authorization request.

## Authorized actions

The contract recognizes two separate future execution intents:

- `SEND_NEGOTIATION_MESSAGE`
- `SUBMIT_COUNTER_TRADE`

Authorizing one does not authorize the other.

The CLI maps:

- `message` -> `SEND_NEGOTIATION_MESSAGE`
- `submit` -> `SUBMIT_COUNTER_TRADE`

## Destination binding

Authorization is bound to one stable destination identifier.

- `SEND_NEGOTIATION_MESSAGE` requires destination type `MANAGER`.
- `SUBMIT_COUNTER_TRADE` requires destination type `LEAGUE`, and the destination ID must exactly equal the proposal league ID.

The policy does not infer a manager destination. The manager destination ID must be supplied explicitly.

The `--` separator is mandatory. Everything before it is parsed using the governed counter-proposal coordinate parser; everything after it is authorization-only input.

## Exact confirmation phrase

The policy generates one canonical confirmation string:

`AUTHORIZE_ONCE action=<ACTION> proposal=<SHA-256> destination=<TYPE>:<ID>`

Authorization succeeds only when supplied confirmation text exactly equals that string. The CLI does not trim, normalize, or change its case.

Generic or standing phrases such as `approved`, `go`, `continue`, or `keep approving recommended` are not valid authorization for a specific proposal.

The public `AuthorizationRequest` constructor independently verifies the canonical phrase so callers cannot manufacture a weaker request.

### Request mode

When `--confirm` is omitted, the CLI prints the fingerprint, requested action, destination, `maxUses = 1`, and exact required `AUTHORIZE_ONCE ...` phrase. No grant is created or persisted.

### Confirmation mode

When `--confirm` is present:

1. the exact phrase is evaluated by the BF-384 policy;
2. a rejected decision persists nothing;
3. an authorized decision is written to the BF-386 trusted grant store;
4. the stored grant remains active and unconsumed; and
5. the CLI performs no external side effect.

The output reports the trusted persisted grant ID and explicitly states that no grant was consumed and no message or trade was sent.

## BF-386/BF-387 durable trusted grant store

`TradeCounterAuthorizationGrantRepository` owns the SQLite table `trade_counter_authorization_grants`.

The durable row stores the governed grant plus nullable `consumed_at` state. Database constraints independently enforce:

- the BF-384 policy ID;
- valid season range;
- lowercase 64-character proposal fingerprint;
- governed action values and destination types;
- `max_uses = 1`;
- message action -> manager destination;
- submit action -> league destination; and
- submit destination ID = proposal league ID.

A partial unique index permits at most one active, unconsumed grant for the same proposal fingerprint + action + destination.

BF-387 treats that database invariant as authoritative. If two exact confirmations race or the user repeats the exact confirmation while an equivalent grant is still active, only one active row can exist. The CLI resolves the collision to the already-active trusted grant ID and does not create a second usable authorization.

A consumed grant is never reactivated. A later authorization for the same intent requires a new explicit authorization event and a new grant.

The persisted row is the future executor's trusted authorization source. Execution code must load it by grant ID and must not trust action, destination, fingerprint, or consumption state supplied by a caller.

## Atomic single-use consumption

Grant consumption remains a separate BF-386 primitive and is **not called by BF-387**.

The repository consumes with one conditional SQLite update bound to grant ID, active state, `max_uses = 1`, fingerprint, action, and destination. Results are:

- `CONSUMED`
- `ALREADY_CONSUMED`
- `MISMATCH`
- `NOT_FOUND`

A mismatch never consumes the grant.

The design intentionally favors at-most-once execution. A future executor should perform final fresh-evidence revalidation and consume the trusted grant immediately before an external side effect. If the platform action then fails, Butler must not silently retry with the same consumed grant; a new explicit authorization is required.

## Fresh-evidence revalidation

Before any future execution, the full proposal pipeline must be rerun from current evidence and produce a fresh BF-382 identity.

`TradeCounterAuthorizationPolicy.revalidate(...)` returns:

- `MATCH`: fingerprint and governed coordinates still match;
- `DRIFTED`: the proposal changed, disappeared, or no longer has an executable identity;
- `INCONCLUSIVE`: fresh proposal evidence is inconclusive.

Only `MATCH` may proceed to a future execution gate.

## Safety boundary

BF-384 through BF-387 do not:

- treat blanket project approval as proposal-specific authorization;
- authorize more than one action or destination;
- authorize an unidentified or inconclusive proposal;
- consume a persisted authorization grant from the CLI;
- send the BF-378 negotiation message;
- submit the BF-380 revised packages;
- infer a manager recipient;
- call an external fantasy platform;
- change Trade Recommendation v5; or
- change BF-369 through BF-383 counter decision semantics.

The next execution capability must load the trusted persisted grant, rerun fresh proposal evidence, require `MATCH`, verify exact action/destination binding, and atomically consume the grant before any external side effect can occur.
