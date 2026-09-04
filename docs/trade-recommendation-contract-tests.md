# Trade recommendation contract tests

The trade recommendation surface is versioned so policy changes remain explicit and auditable. Contract tests retain the historical policy identifiers while also locking the identifiers used by the current material-loss recommendation path.

## Locked recommendation identifiers

Historical compatibility layers:

- `trade-recommendation-v1-conservative-evidence-first`
- `trade-recommendation-v2-market-first-strategic-veto`
- `trade-strategic-veto-v1-explicit-weakness-protection`

Current live material-loss path:

- `trade-recommendation-v3-market-first-material-loss-veto`
- `trade-strategic-veto-v2-material-protected-value-loss`
- `trade-protected-value-flow-v1-current-valued-assets`
- `trade-protected-value-materiality-v1-25-percent-loss`
- `trade-team-perspective-v1-explicit-owner`

The v1 presence-based detector remains locked for audit compatibility, but the live `trade recommendation` command uses the v3 recommendation policy and v2 material-loss detector.

## Locked team-action vocabulary

- `ACCEPT`
- `REJECT`
- `HOLD`
- `INCONCLUSIVE`

The lower-level veto state vocabulary remains `CLEAR` / `BLOCKED`. When required governed evidence is incomplete, the CLI presents `Strategic veto: NOT_EVALUATED`; that is a presentation state and does not add a third lower-level veto state.

These tests are compatibility guards. They do not change the 25% material-loss threshold, evidence gates, perspective mapping, protected-value aggregation, weighting, side-flipping rules, posture behavior, or introduce `COUNTER` behavior.
