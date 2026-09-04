# Sleeper Counter Trade Expectation Snapshot

BF-406 freezes the provider identities and exact asset movement needed to reconcile a manually submitted Sleeper counter trade later.

Policy: `sleeper-counter-trade-expectation-snapshot-v1-handoff-provider-movement`

## Purpose

Sleeper's supported public API is read-only. Butler therefore presents an authorized counter for manual submission and later reads Sleeper transactions to verify what happened.

Provider identity and local ownership evidence can change after the user submits the trade and Butler synchronizes league state. BF-406 captures the stable Sleeper coordinates before that can happen.

## Input boundary

A snapshot requires:

- a durable BF-402 manual handoff presentation,
- action `SUBMIT_COUNTER_TRADE`,
- reconciliation mode `SLEEPER_TRANSACTION_READBACK`,
- the trusted Butler league destination,
- exact Side A and Side B Butler team IDs from the freshly governed trade context,
- the complete BF-380 revised Side A and Side B packages.

The existing BF-399 resolver must prove all provider mappings and current ownership at snapshot time.

Message handoffs cannot create trade snapshots.

## Snapshot contents

The durable snapshot stores only stable provider/movement coordinates:

- Butler league ID,
- Side A and Side B Butler team IDs,
- Sleeper league ID,
- the two Sleeper roster IDs,
- exact Sleeper player adds,
- exact Sleeper player drops,
- exact Sleeper draft-pick movements,
- canonical movement JSON,
- SHA-256 of that canonical movement,
- first snapshot timestamp,
- BF-399 resolver provenance.

The movement JSON is deterministic: roster IDs, player keys, and draft-pick movements are serialized in governed sorted order.

## Deliberately excluded

BF-406 does not store or infer:

- Sleeper week/round for transaction lookup,
- creator user ID,
- reconciliation not-before timestamp,
- reconciliation result,
- execution success/failure,
- authorization consumption.

The later reconciliation surface must take an explicit Sleeper week and use the BF-402 first `presented_at` value as the not-before boundary.

## Immutability and idempotence

There is at most one snapshot per trusted execution claim/handoff.

Repeating the exact snapshot operation returns the existing snapshot and preserves its original timestamp.

A different provider movement cannot replace an existing snapshot. SQLite also rejects direct updates to snapshot rows.

## Safety boundary

BF-406 performs no Sleeper network request and no Sleeper write.

It does not mark a trade submitted, matched, completed, successful, failed, or unknown. It only freezes the evidence coordinates that a later official read-only reconciliation may use.
