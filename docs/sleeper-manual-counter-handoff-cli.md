# Sleeper Manual Counter Handoff CLI

BF-405 exposes Butler's governed manual Sleeper handoff path through one trusted-grant-only command:

`butler trade counter-handoff <trusted-grant-id>`

The command accepts no caller-supplied league, trade package, perspective, action, destination, message text, or trade JSON. Those values must already be bound to trusted persisted authorization and replay state.

## Execution flow

For the trusted grant ID, Butler:

1. loads the persisted one-shot authorization grant;
2. fails closed if the grant is already consumed;
3. loads the immutable original Side A / Side B replay context;
4. reconstructs the exact authorized trade coordinates;
5. reruns the governed counter pipeline from current evidence;
6. assesses BF-391 fresh execution readiness;
7. requires `READY` before any execution attempt is created;
8. derives the exact BF-403 governed execution payload;
9. uses BF-404 to prepare/recover the BF-392 attempt, atomically claim/recover BF-393, and persist/recover BF-402 manual handoff presentation;
10. reloads the exact BF-401 trusted handoff artifact for display.

The route is isolated as `TRADE_COUNTER_HANDOFF` in `ButlerCommandRouter`.

## Output

A presented handoff shows:

- trusted grant ID;
- fresh readiness state;
- coordinator state and reason;
- execution attempt ID;
- execution claim ID;
- immutable handoff presentation ID;
- first `presented_at` timestamp;
- authorized action and destination;
- exact payload kind and SHA-256;
- reconciliation mode;
- exact governed handoff payload;
- explicit manual-only warning.

For `SUBMIT_COUNTER_TRADE`, the command states that official Sleeper transaction readback is available and that the persisted first-presentation time is the safe not-before boundary for later reconciliation.

For `SEND_NEGOTIATION_MESSAGE`, the command states that Sleeper provides no supported official message readback.

## Meaning of presentation

`HANDOFF_PRESENTED` and `HANDOFF_ALREADY_PRESENTED` mean only that Butler durably presented the exact authorized payload for manual Sleeper action.

They do **not** mean:

- the manager message was sent;
- the trade was submitted;
- Sleeper accepted anything;
- the action succeeded;
- the authorization grant was consumed.

After presentation, the execution attempt remains `IN_FLIGHT` and the authorization grant remains active/unconsumed until a separately governed confirmed outcome closes it.

## Safety boundary

BF-405 does not:

- accept raw payload bytes;
- accept alternate trade/action/destination coordinates;
- bypass fresh readiness;
- bypass the atomic execution claim;
- call an unsupported Sleeper write endpoint;
- send a message;
- submit a trade;
- terminalize execution;
- infer completion from presentation.

Sleeper's supported public API remains read-only for writes, so the actual action must be completed manually in Sleeper.
