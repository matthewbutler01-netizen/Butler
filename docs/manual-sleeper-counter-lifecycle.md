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

The trade and message local status commands may now report explicit no-action states:

- `NO_ACTION_ACKNOWLEDGED_PENDING_FINALIZATION`: exact durable human no-action evidence exists, but the local attempt is not yet terminalized.
- `NO_ACTION_FINALIZED`: exact durable no-action evidence was separately finalized; the local attempt is `FAILED`, the one-shot grant is closed, and any retry requires fresh authorization.

Successful and no-action completion are separate provenance paths. If contradictory success and no-action evidence somehow coexist, the status inspector fails closed rather than choosing one interpretation.

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
- Successful finalization and no-action finalization both close the one-shot authorization, so any later retry requires a fresh explicit authorization.
- Butler performs no Sleeper write/private API call in any of these commands.
