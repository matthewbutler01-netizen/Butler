# Trade Counter Proposal

BF-376 adds Butler's first governed `COUNTER` proposal surface while keeping Trade Recommendation v5 and the earlier counter-evidence surfaces frozen.

Proposal policy: `trade-counter-proposal-v1-selected-candidate-read-only-counter`

BF-377 adds an auditable original-trade envelope:

Envelope policy: `trade-counter-proposal-envelope-v1-original-trade-perspective-bound`

CLI:

`butler trade counter-proposal <league-id> <season> <side-a-assets> <side-b-assets> <side-a|side-b> [source] [--minimum-as-of YYYY-MM-DD]`

This command is read-only. It does not submit, send, accept, reject, mutate, or execute a trade.

## Inputs

The proposal policy consumes exactly two governed upstream artifacts:

1. BF-374 counter opportunity (`trade-counter-opportunity-v1-v5-reject-plus-strategic-eligibility`); and
2. BF-375 candidate selection (`trade-counter-candidate-selection-v1-unique-best-market-criteria-fail-ambiguous`).

The live CLI constructs those artifacts from the same explicit league, season, team perspective, value source, and minimum-as-of boundary used by Trade Recommendation v5.

## Action vocabulary

BF-376 introduces a counter-proposal action vocabulary separate from the v5 team-action vocabulary:

- `COUNTER`
- `NO_ACTION`
- `INCONCLUSIVE`

This does not add `COUNTER` to Trade Recommendation v5. v5 remains limited to `ACCEPT`, `REJECT`, `HOLD`, and `INCONCLUSIVE`.

## Gate

The gate is strict:

- BF-374 `INCONCLUSIVE` + BF-375 `INCONCLUSIVE` -> `INCONCLUSIVE`;
- BF-374 `NO_COUNTER` + BF-375 `NO_SELECTION` -> `NO_ACTION`;
- BF-374 `COUNTER_AVAILABLE` + BF-375 `AMBIGUOUS` -> `NO_ACTION`;
- BF-374 `COUNTER_AVAILABLE` + BF-375 `SELECTED` -> `COUNTER`.

Any incompatible upstream state combination is rejected as a contract violation rather than interpreted optimistically.

Therefore a `COUNTER` can only exist after all of these conditions have already been satisfied:

1. live v5 evidence is complete;
2. the selected team perspective receives `REJECT`;
3. at least one market-fair single-asset candidate exists;
4. the candidate survives bilateral strategic veto protection;
5. the candidate is strategically eligible; and
6. BF-375 finds a unique best candidate on governed market-selection criteria.

## Proposal payload

A `COUNTER` proposal copies the exact BF-375 selected candidate without reinterpretation. It includes:

- market rank;
- adjustment type;
- package side;
- player or draft-pick identity;
- current owner/team metadata;
- market value and as-of date;
- required value change and excess value;
- resulting Side A and Side B values;
- resulting fairness gap; and
- resulting `MARKET_FAIR` classification.

The proposal operation is rendered explicitly:

- `ADD <asset> TO SIDE_A|SIDE_B`; or
- `REMOVE <asset> FROM SIDE_A|SIDE_B`.

The policy does not infer a different asset, swap the target side, convert add to remove, or create a multi-asset package.

## BF-377 original-trade envelope

BF-377 binds every proposal result to:

- explicit team perspective;
- perspective policy provenance;
- original Side A player IDs and draft-pick IDs;
- original Side B player IDs and draft-pick IDs;
- league, season, source, and minimum-as-of coordinates; and
- the BF-376 proposal result and action.

The envelope is not cosmetic metadata. It verifies proposal integrity against the original packages before the live CLI renders the binding as verified.

For `ADD_ASSET_TO_LOWER_PACKAGE`:

- the proposed asset must be absent from both original trade packages.

For `REMOVE_ASSET_FROM_HIGHER_PACKAGE`:

- the proposed asset must exist on the governed original side; and
- removing it may not leave that side empty.

The envelope also rejects duplicate asset IDs within an original package and rejects player/pick overlap across the two original sides.

These checks make it harder for a proposal artifact to be copied, cached, or consumed against a different trade than the one that produced it.

## Ambiguity

If BF-375 returns `AMBIGUOUS`, BF-376 returns `NO_ACTION` with reason `AMBIGUOUS_SELECTION`.

Deterministic tail ordering such as player/pick type or asset ID still cannot manufacture a `COUNTER` decision.

BF-377 still binds the explicit perspective and original packages for non-COUNTER outcomes, but no proposal payload is attached.

## Safety boundary

BF-376/BF-377 do not:

- submit a trade to Sleeper or any other platform;
- send a message to another manager;
- automatically negotiate;
- alter league rosters or draft picks;
- create multi-asset counter packages;
- weaken strategic vetoes;
- change the 5% fairness band;
- change BF-369 ranking;
- change BF-373 eligibility;
- change BF-374 opportunity semantics;
- change BF-375 selection semantics; or
- modify Trade Recommendation v5.

A future execution/integration capability would require a separate explicit authorization and versioned contract. A read-only `COUNTER` proposal must never be treated as permission to send or execute a trade.
