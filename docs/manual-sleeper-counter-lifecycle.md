# Governed manual Sleeper counter lifecycle

Butler deliberately separates decision support, manual platform action, read-only verification, explicit human evidence, and local finalization. The official Sleeper API access used by this lifecycle is read-only. Butler does not use a private or write endpoint to send negotiation messages or submit counter trades.

## Shared handoff boundary

A governed counter reaches the manual platform boundary through:

```text
butler trade counter-handoff <trusted-grant-id>
```

The handoff is a durable record that Butler presented the exact governed payload for a trusted claim. Presentation is not proof that a person sent the message or submitted the trade, and it does not mark the execution successful, failed, or consume the authorization grant.

Every presented handoff has two broad human outcomes:

1. the person performs the exact manual action outside Butler and follows the action-specific success-evidence path; or
2. the person does not perform that exact manual action and may explicitly record durable no-action evidence.

Butler never infers the second case merely because external evidence is absent.

## Manual trade path

Use the local status command first when you only need to inspect what Butler has durably recorded:

```text
butler trade counter-status <trusted-grant-id>
```

`counter-status` performs no Sleeper request. It distinguishes ordinary pending/success states from explicit no-action acknowledgment/finalization states. Absence of a local terminal outcome is never treated as evidence that Sleeper has not completed the trade.

When the trade was manually submitted and current external evidence is required, use the separate explicit-week reconciliation command:

```text
butler trade counter-reconcile <trusted-grant-id> <sleeper-week>
```

This performs an official GET-only Sleeper transaction read. The week must be supplied explicitly. A complete match remains evidence only; reconciliation does not change execution state or consume authorization. `NO_MATCH`, pending, ambiguous, or inconclusive evidence is not converted into failure.

If local success finalization is intended, use:

```text
butler trade counter-finalize <trusted-grant-id> <sleeper-week>
```

Finalization rechecks the governed GET-only evidence. Only exact confirmed completed readback can mark the matching local attempt `SUCCEEDED` and consume its one-shot authorization. Missing, pending, ambiguous, inconclusive, or mismatched evidence fails closed.

If the presented trade handoff was **not submitted at all**, do not use missing transaction evidence as proof of that fact. Record explicit human no-action evidence instead:

```text
butler trade counter-no-action-ack <trusted-grant-id> --confirm NO_EXTERNAL_ACTION_TAKEN
```

The phrase is exact and case/whitespace sensitive. This records local evidence only; the attempt remains `IN_FLIGHT` and the grant remains active until the separate no-action finalization step:

```text
butler trade counter-no-action-finalize <trusted-grant-id>
```

Eligible no-action finalization atomically marks the matching local attempt `FAILED` and consumes/closes the one-shot authorization. This means the old authorization cannot later be reused; any retry requires a fresh explicit authorization. This path performs no Sleeper request and does not claim that a failed platform operation occurred. `FAILED` means the governed manual execution lifecycle was explicitly closed because the user confirmed the external action was not taken.

A usable provider expectation snapshot is required for trade reconciliation/success verification, but it is not required to close a handoff through exact human no-action evidence.

### Trade no-action supersession and post-closure discrepancy

Human no-action evidence is immutable historical evidence, but an **unfinalized** no-action acknowledgment is not allowed to overrule later exact provider truth. If the user records `NO_EXTERNAL_ACTION_TAKEN`, then manually submits the trade before no-action finalization, and Butler later obtains exact completed Sleeper readback matching the frozen handoff, `counter-finalize` may atomically:

1. persist a durable `SUPERSEDED_BY_CONFIRMED_TRADE` resolution bound to the original no-action acknowledgment and exact completed transaction;
2. preserve the original no-action acknowledgment unchanged for audit history; and
3. finalize the active local execution as `SUCCEEDED` and consume the one-shot authorization.

The supersession record and successful terminalization are committed together. A trade success outcome cannot coexist with an unresolved no-action acknowledgment.

Once no-action has already been finalized as `FAILED + CONSUME`, the lifecycle is permanently closed. Later exact completed Sleeper readback **does not rewrite** the historical terminal state or reopen the authorization. Instead Butler records a durable `POST_CLOSURE_EXTERNAL_ACTION` discrepancy containing the exact transaction evidence and reports that the external action requires investigation.

This distinction prevents two bad outcomes: stale human evidence cannot defeat exact completed provider evidence while the lifecycle is still active, and later external activity cannot silently rewrite an already-closed governed history.

## Manual message path

Because Sleeper does not provide the same official readback for a manually sent negotiation message, Butler does not infer delivery. Inspect local state with:

```text
butler trade counter-message-status <trusted-grant-id>
```

If the user sends the exact reviewed message outside Butler, only after that manual action should the user explicitly record sent-message acknowledgment evidence:

```text
butler trade counter-message-ack <trusted-grant-id> --confirm SENT_EXACT_MESSAGE
```

The confirmation phrase is exact. Recording the acknowledgment does not send a message, mark the attempt successful, or consume authorization.

Local successful completion is a separate command:

```text
butler trade counter-message-finalize <trusted-grant-id>
```

This requires the pre-existing durable exact-message acknowledgment. It does not create acknowledgment implicitly and performs no Sleeper write or private API call.

If the presented message was **not sent**, use the shared explicit no-action path instead:

```text
butler trade counter-no-action-ack <trusted-grant-id> --confirm NO_EXTERNAL_ACTION_TAKEN
butler trade counter-no-action-finalize <trusted-grant-id>
```

The no-action acknowledgment is mutually exclusive with durable `SENT_EXACT_MESSAGE` evidence for the same message handoff. Butler enforces that conflict in both insertion orders. No-action finalization closes the local attempt as `FAILED` and closes the old one-shot authorization; it does not claim a message-send failure because Butler never sent the message.

## Status interpretation

The trade and message local status commands may report explicit no-action states:

- `NO_ACTION_ACKNOWLEDGED_PENDING_FINALIZATION`: exact durable human no-action evidence exists, but the local attempt is not yet terminalized.
- `NO_ACTION_FINALIZED`: exact durable no-action evidence was separately finalized; the local attempt is `FAILED`, the one-shot grant is closed, and any retry requires fresh authorization.

Trade status may additionally report governed resolution states:

- `FINALIZED_AFTER_NO_ACTION_SUPERSESSION`: exact completed Sleeper readback superseded an earlier **unfinalized** trade no-action acknowledgment, which remains immutable historical evidence; the local execution finalized `SUCCEEDED`.
- `POST_CLOSURE_EXTERNAL_ACTION_DISCREPANCY`: exact completed Sleeper readback appeared only after the no-action lifecycle had already finalized `FAILED + CONSUME`; the closed local history remains unchanged and the external action requires investigation.

Success and no-action evidence without the matching governed resolution remain contradictory. The status inspector fails closed rather than choosing one interpretation.

## Safety invariants

- Sleeper message sending and trade submission remain manual actions outside Butler.
- Handoff presentation is not dispatch, success, failure, or no-action evidence.
- Local status commands perform no Sleeper request and do not mutate lifecycle state.
- Trade reconciliation uses only official GET-only transaction evidence and requires an explicit Sleeper week.
- Absence of matching transaction evidence is never inferred to mean failure or no external action.
- Message delivery is never inferred from handoff presentation.
- A message can only be finalized successfully from an explicit durable human acknowledgment of the exact trusted message.
- A trade can only be finalized successfully from exact completed readback matching the frozen governed provider movement.
- No-action closure requires exact `NO_EXTERNAL_ACTION_TAKEN` human evidence bound to the trusted handoff; it is never inferred.
- Recording no-action evidence alone does not terminalize the attempt or consume authorization.
- No-action finalization is a separate atomic local operation that marks `FAILED` and closes the one-shot grant.
- An unfinalized trade no-action acknowledgment may be superseded only by exact completed Sleeper readback bound to the same trusted handoff; supersession and success terminalization are atomic.
- A no-action lifecycle already finalized `FAILED + CONSUME` is never rewritten by later external evidence; exact later completion is recorded only as a post-closure discrepancy requiring investigation.
- Successful finalization and no-action finalization both close the one-shot authorization, so any later retry requires a fresh explicit authorization.
- Butler performs no Sleeper write/private API call in any of these commands.
