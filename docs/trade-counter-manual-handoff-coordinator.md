# Trade Counter Manual Handoff Coordinator

BF-404 coordinates Butler's durable local execution path after a trusted counter authorization has been freshly revalidated as `READY`.

Coordinator: `trade-counter-manual-handoff-coordinator-v1-payload-prepare-claim-present`

The coordinator performs no external write. It turns fresh governed evidence into a durable, recoverable manual handoff state.

## Sequence

For one trusted authorization grant, the coordinator:

1. verifies the supplied BF-391 readiness coordinates exactly match the trusted grant;
2. requires readiness state `READY`;
3. invokes BF-403 to materialize the exact governed payload from the fresh proposal artifacts;
4. prepares or recovers the BF-392 immutable execution attempt;
5. atomically creates or recovers the BF-393 READY execution claim, moving the attempt to `IN_FLIGHT`;
6. prepares the BF-401 Sleeper manual handoff from the persisted trusted claim;
7. persists or recovers the immutable BF-402 first-presentation record.

The sequence is intentionally recoverable. A process interruption after one local durable step can be retried with the same exact inputs:

- BF-392 recovers the same execution attempt for identical intent;
- BF-393 recovers the same claim;
- BF-402 preserves the original first `presented_at` timestamp.

## States

- `HANDOFF_PRESENTED`
- `HANDOFF_ALREADY_PRESENTED`
- `READINESS_NOT_READY`
- `PAYLOAD_NOT_AVAILABLE`
- `CLAIM_FAILED`
- `HANDOFF_NOT_AVAILABLE`

Only the first two states represent a durable manual handoff presentation. Neither means the manager message was sent or the trade was submitted in Sleeper.

## Authorization lifecycle

Manual presentation does not consume the one-shot authorization grant and does not terminalize the execution attempt.

After successful presentation:

- the BF-392 attempt remains `IN_FLIGHT`;
- the BF-393 claim remains the durable execution lock;
- the BF-402 first presentation is immutable;
- the authorization remains active until a separately governed confirmed outcome closes it.

For trade submission handoffs, BF-402 `presented_at` can serve as the not-before boundary for official Sleeper transaction reconciliation.

## Safety boundary

BF-404 does not:

- accept caller-supplied payload text;
- create authorization grants;
- weaken or bypass BF-391 readiness;
- bypass the BF-393 atomic claim;
- send a manager message;
- submit a trade;
- call a Sleeper write endpoint;
- mark execution `SUCCEEDED`, `FAILED`, or `UNKNOWN`;
- consume authorization;
- infer an external outcome.

A later CLI slice may invoke this coordinator from a trusted grant ID after reconstructing the fresh governed counter artifacts from the immutable replay context.
