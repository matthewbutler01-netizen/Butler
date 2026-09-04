# Trade Counter Manual Terminal Guards

BF-415 centralizes manual terminal-outcome extensions to Butler's database-level execution/grant guards.

Installer ID:

`trade-counter-manual-terminal-guard-installer-v1-generic-plus-sleeper-trade`

## Problem

BF-396 introduced SQLite triggers that prevent a claimed execution attempt from moving from `IN_FLIGHT` to a terminal state, or consuming its authorization grant, without durable governed executor-outcome provenance.

BF-410 added a second legitimate terminal path: exact completed Sleeper trade readback. Before BF-415, BF-410 independently dropped and recreated the same BF-396 trigger names with trade support embedded in its coordinator.

That works for the two current paths, but duplicating shared trigger ownership becomes fragile as additional manual completion types are added.

## BF-415 structure

BF-396 remains the base generic executor-outcome guard.

`TradeCounterManualTerminalGuardInstaller` now owns manual extensions. The BF-410 Sleeper trade coordinator:

1. initializes the generic BF-396 outcome schema,
2. creates its own immutable Sleeper trade terminal-outcome table and provenance triggers,
3. calls the shared manual terminal guard installer.

The shared installer then atomically replaces the two shared guard triggers with a combined form that recognizes:

- BF-395/BF-396 generic executor terminal outcomes,
- BF-396 UNKNOWN resolutions for grant consumption,
- BF-410 exact Sleeper trade `SUCCEEDED` outcomes.

## Initializer-order guarantee

Calling the generic BF-396 coordinator after the manual installer does not remove manual support because BF-396 creates its base triggers with `IF NOT EXISTS`.

Calling BF-410 after BF-396 upgrades the base guards through the shared installer.

BF-415 tests both orders and repeated installer invocation.

This gives future manual terminal outcome types one extension point instead of competing for the same SQLite trigger names.

## Fail-closed behavior

The shared installer refuses to run unless all required current outcome tables exist:

- `trade_counter_execution_outcomes`,
- `trade_counter_execution_unknown_resolutions`,
- `sleeper_counter_trade_terminal_outcomes`.

Direct terminal-state or authorization-consumption bypass remains guarded exactly as before; existing BF-396 and BF-410 behavioral tests continue to validate those protections.

## Safety boundary

BF-415 is a refactor-only governance change.

It does not:

- add a new terminal outcome type,
- terminalize a manual message acknowledgment,
- change BF-410 trade evidence semantics,
- change BF-395 executor outcome semantics,
- consume any authorization by itself,
- perform any Sleeper read or write action.

A future manual-message finalizer must extend this shared installer rather than independently replacing the shared trigger names.
