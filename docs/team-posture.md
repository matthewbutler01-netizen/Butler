# Team posture policy

Butler derives team posture from two independently governed league-relative dimensions:

- competitive-performance tier: `FRONT_TIER`, `MIDDLE_TIER`, `BACK_TIER`, or `INSUFFICIENT_EVIDENCE`
- current-roster-strength tier: `FRONT_ROSTER_TIER`, `MIDDLE_ROSTER_TIER`, `BACK_ROSTER_TIER`, or `INSUFFICIENT_EVIDENCE`

Policy ID: `team-posture-v1-tier-agreement`

## Agreement matrix

| Competitive tier | Roster-strength tier | Team posture |
| --- | --- | --- |
| `FRONT_TIER` | `FRONT_ROSTER_TIER` | `CONTENDER` |
| `BACK_TIER` | `BACK_ROSTER_TIER` | `REBUILDER` |
| any other sufficient combination | any other sufficient combination | `MIDDLE_OR_MIXED` |
| `INSUFFICIENT_EVIDENCE` | any | `INSUFFICIENT_EVIDENCE` |
| any | `INSUFFICIENT_EVIDENCE` | `INSUFFICIENT_EVIDENCE` |

The policy uses no weighted composite score. Current results cannot override roster strength, and roster strength cannot override current results. A team receives a strong posture label only when both governed dimensions agree.

## Evidence boundaries

Competitive performance is based on the governed league-relative performance tier. Roster strength is based on the governed league-relative current-roster tier. Their underlying evidence gates and provenance remain authoritative.

League/team identity must align across the two reports. Butler fails closed if league IDs, team sets, or team names drift rather than attaching posture to the wrong franchise.

## Interpretation

`CONTENDER` means current competitive results and current roster strength both fall in their governed front tiers. `REBUILDER` means both dimensions fall in their governed back tiers. `MIDDLE_OR_MIXED` includes true middle teams and disagreement cases, such as strong current results with weak roster strength or a strong roster with poor current results.

Team posture is strategic context. It is not by itself a trade recommendation, accept/reject decision, player-value adjustment, market-value override, or instruction to buy/sell assets. Draft capital is not part of the current-roster-strength ranking and is therefore not silently folded into posture.

## CLI

```text
butler league team-posture <league-id> <season> [roster-value-source]
```

The CLI prints posture, the two input tiers, policy IDs, sources, and availability so the classification remains inspectable.
