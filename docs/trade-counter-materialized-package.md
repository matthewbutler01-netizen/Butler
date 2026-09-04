# Trade Counter Materialized Package

BF-380 turns a BF-377 bound single-asset counter envelope into the complete revised Side A and Side B package identities. BF-381 exposes those revised packages through the existing read-only `trade counter-proposal` CLI.

Policy: `trade-counter-materialized-package-v1-bound-single-adjustment`

CLI:

`butler trade counter-proposal <league-id> <season> <side-a-assets> <side-b-assets> <side-a|side-b> [source] [--minimum-as-of YYYY-MM-DD]`

This policy and CLI output are read-only. They do not submit, send, accept, reject, or mutate a trade.

## States

- `MATERIALIZED`
- `NO_PACKAGE`
- `INCONCLUSIVE`

Mapping is strict:

- envelope `COUNTER` -> `MATERIALIZED`;
- envelope `NO_ACTION` -> `NO_PACKAGE`;
- envelope `INCONCLUSIVE` -> `INCONCLUSIVE`.

Only `MATERIALIZED` carries revised packages.

## Materialization rule

The policy starts from the BF-377 normalized original Side A and Side B packages and applies exactly the bound BF-376 single-asset proposal operation to the governed side.

- player add: append the selected player ID to that side's player list;
- draft-pick add: append the selected pick ID to that side's pick list;
- player remove: remove the selected player ID from that side;
- draft-pick remove: remove the selected pick ID from that side.

The untouched package is preserved exactly. Existing asset order is preserved, and a newly added asset is appended.

The policy rejects a duplicate add, a missing removal target, or any materialization that would leave a trade side empty. BF-377 already validates these conditions against the original trade; BF-380 keeps its own transformation guards so the materialized artifact fails closed if upstream artifacts are ever inconsistent.

## Provenance

The materialized artifact retains:

- league ID;
- season;
- value source;
- minimum-as-of boundary;
- explicit team perspective;
- original Side A package;
- original Side B package;
- revised Side A package; and
- revised Side B package.

## CLI exposure

BF-381 materializes the packages only after the BF-377 envelope has been built and verified. The existing `trade counter-proposal` command prints:

- materialized-package policy provenance;
- state and reason;
- complete revised Side A package; and
- complete revised Side B package.

For `NO_PACKAGE` or `INCONCLUSIVE`, the CLI prints that no revised counter packages are available and does not synthesize a trade.

The package snapshot appears on the same read-only proposal surface as the governed COUNTER and BF-378 negotiation wording.

## Safety boundary

BF-380/BF-381 do not:

- recompute recommendation or strategic evidence;
- choose another candidate;
- create a multi-asset counter;
- submit a trade;
- send a negotiation message;
- mutate rosters or draft-pick ownership;
- change BF-369 through BF-379 semantics; or
- modify Trade Recommendation v5.

Any platform execution remains a separate authorization boundary.
