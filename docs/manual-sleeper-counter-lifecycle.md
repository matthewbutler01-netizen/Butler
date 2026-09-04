# Governed manual Sleeper counter lifecycle

Butler deliberately separates decision support, manual platform action, read-only verification, and local finalization. The official Sleeper API access used by this lifecycle is read-only. Butler does not use a private or write endpoint to send negotiation messages or submit counter trades.

## Shared handoff boundary

A governed counter reaches the manual platform boundary through:

```text
butler trade counter-handoff <trusted-grant-id>
```

The handoff is a durable record that Butler presented the exact governed payload for a trusted claim. Presentation is not proof that a person sent the message or submitted the trade, and it does not mark the execution successful or consume the authorization grant.

## Manual trade path

Use the local status command first when you only need to inspect what Butler has durably recorded:

```text
butler trade counter-status <trusted-grant-id>
```

`counter-status` performs no Sleeper request. It can report a missing provider snapshot, a locally unfinalized lifecycle, or a previously finalized lifecycle. Absence of a local terminal outcome is never treated as evidence that Sleeper has not completed the trade.

When current external evidence is required, use the separate explicit-week reconciliation command:

```text
butler trade counter-reconcile <trusted-grant-id> <sleeper-week>
```

This performs an official GET-only Sleeper transaction read. The week must be supplied explicitly. A complete match remains evidence only; reconciliation does not change execution state or consume authorization. `NO_MATCH`, pending, ambiguous, or inconclusive evidence is not converted into failure.

If local finalization is intended, use:

```text
butler trade counter-finalize <trusted-grant-id> <sleeper-week>
```

Finalization rechecks the governed GET-only evidence. Only exact confirmed completed readback can mark the matching local attempt `SUCCEEDED` and consume its one-shot authorization. Missing, pending, ambiguous, inconclusive, or mismatched evidence fails closed.

## Manual message path

Because Sleeper does not provide the same official readback for a manually sent negotiation message, Butler does not infer delivery. Inspect local state with:

```text
butler trade counter-message-status <trusted-grant-id>
```

The user sends the exact reviewed message outside Butler. Only after that manual action should the user explicitly record acknowledgment evidence:

```text
butler trade counter-message-ack <trusted-grant-id> --confirm SENT_EXACT_MESSAGE
```

The confirmation phrase is exact. Recording the acknowledgment does not send a message, mark the attempt successful, or consume authorization.

Local completion is a separate command:

```text
butler trade counter-message-finalize <trusted-grant-id>
```

This requires the pre-existing durable exact-message acknowledgment. It does not create acknowledgment implicitly and performs no Sleeper write or private API call.

## Safety invariants

- Sleeper message sending and trade submission remain manual actions outside Butler.
- Handoff presentation is not dispatch or success evidence.
- Local status commands perform no Sleeper request and do not mutate lifecycle state.
- Trade reconciliation uses only official GET-only transaction evidence and requires an explicit Sleeper week.
- Absence of matching transaction evidence is never inferred to mean failure.
- Message delivery is never inferred from handoff presentation.
- A message can only be finalized from an explicit durable human acknowledgment of the exact trusted message.
- A trade can only be finalized from exact completed readback matching the frozen governed provider movement.
- Finalization is the only step in these manual paths that may mark the matching local attempt `SUCCEEDED` and consume its one-shot authorization.
