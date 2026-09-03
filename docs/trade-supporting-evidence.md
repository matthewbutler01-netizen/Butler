# Trade Supporting Evidence

Butler's trade supporting-evidence surface places governed player evidence beside persisted market values without changing those values or turning the evidence into a recommendation.

## CLI

```text
butler trade supporting-evidence <league-id> <season> <side-a-player-ids> <side-b-player-ids> [source]
```

`side-a-player-ids` and `side-b-player-ids` are comma-separated Butler player IDs. This surface is intentionally player-only. Draft picks remain supported by the established `trade compare` workflow, but they do not receive age-outlook evidence because the aging model applies to NFL players rather than draft-pick assets.

If `source` is omitted, Butler resolves the league's normal player-value source. An explicit source uses the same validation rules as `TradeValueAnalyzer`.

## Output contract

The trade evidence package contains two independent dimensions:

1. **Market value** — persisted player values, coverage, side totals, and the numeric A-minus-B value difference when value coverage is complete.
2. **Supporting evidence** — governed per-player evidence flags with signal, dimension, policy ID, evidence source, and summary.

Supporting evidence is attached by player ID. A player may have zero supporting flags. Zero flags do not make a market-value comparison incomplete and do not imply favorable or unfavorable aging evidence.

Market-value completeness remains authoritative. If a traded player is missing market value, the trade remains incomplete and Butler does not manufacture a value difference even if supporting evidence exists for that player.

## Aging evidence semantics

The current supporting-evidence provider is the governed age-outlook layer. Its flags use:

- `FAVORABLE`
- `UNFAVORABLE`
- `INCONCLUSIVE`

These are per-metric descriptive labels. They are not player grades. Multiple flags are not summed, averaged, voted, weighted, or converted into a player-level age score.

The package preserves the aging support policy, outlook policy, model-age as-of coordinate, and model data-source provenance. Composition fails closed if league identity or evidence subject identity is inconsistent.

## Explicit non-goals

This surface does **not**:

- alter a player's persisted market value;
- alter either trade side's total value;
- alter market-value completeness or coverage;
- alter the numeric A-minus-B value difference;
- apply a fairness threshold;
- declare a trade winner;
- produce buy, sell, hold, accept, reject, or counter recommendations;
- aggregate age flags into a hidden score or weight.

Any future fairness, recommendation, or weighting semantics require a separately governed policy and must not be inferred from this evidence package.

## Related commands

```text
butler trade compare <league-id> <side-a-assets> <side-b-assets> [source] [--minimum-as-of YYYY-MM-DD]
butler league supporting-evidence <league-id> <season>
butler league player-evidence-profile <league-id> [season] [--age-as-of YYYY-MM-DD] [--minimum-profile-as-of YYYY-MM-DD]
```

Use `trade compare` for the established asset-level value comparison, including supported draft picks. Use `trade supporting-evidence` when the trade is player-only and the question is what governed player evidence exists beside market value without interpreting it strategically.
