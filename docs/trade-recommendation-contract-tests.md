# Trade recommendation contract tests

BF-295 locks the externally meaningful recommendation policy identifiers and action vocabulary used by the conservative trade recommendation surface.

Locked identifiers:

- `trade-recommendation-v1-conservative-evidence-first`
- `trade-team-perspective-v1-explicit-owner`

Locked team-perspective action vocabulary:

- `ACCEPT`
- `REJECT`
- `HOLD`
- `INCONCLUSIVE`

This is a compatibility guard only. It does not change recommendation thresholds, evidence gates, perspective mapping, weighting, strategic overrides, or introduce `COUNTER` behavior.
