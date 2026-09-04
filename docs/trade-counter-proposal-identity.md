# Trade Counter Proposal Identity

BF-382 adds a deterministic audit fingerprint for a bound, materialized read-only `COUNTER` proposal.

Policy: `trade-counter-proposal-identity-v1-canonical-bound-packages-sha256`

Algorithm: `SHA-256`

Canonical encoding version: `1`

The fingerprint identifies the exact governed proposal evidence that was reviewed. It is **not** an authorization token and does not grant permission to send a message, submit a trade, or mutate league state.

## States

- `IDENTIFIED`
- `NO_IDENTITY`
- `INCONCLUSIVE`

Mapping is strict:

- BF-377 envelope `COUNTER` + BF-380 `MATERIALIZED` -> `IDENTIFIED`;
- envelope `NO_ACTION` + `NO_PACKAGE` -> `NO_IDENTITY`;
- envelope `INCONCLUSIVE` + materialized `INCONCLUSIVE` -> `INCONCLUSIVE`.

Only `IDENTIFIED` carries a fingerprint. This prevents a no-action or incomplete proposal from receiving an executable-looking identifier.

## Canonical input

The fingerprint uses deterministic length-prefixed binary encoding rather than delimiter-based text concatenation.

The canonical payload includes:

- identity policy ID and canonical version;
- BF-376 proposal policy ID;
- BF-377 envelope policy ID;
- BF-380 materialized-package policy ID;
- league ID;
- season;
- value source;
- minimum-as-of boundary, including explicit null state;
- explicit team perspective;
- ordered original Side A player and pick IDs;
- ordered original Side B player and pick IDs;
- ordered revised Side A player and pick IDs;
- ordered revised Side B player and pick IDs;
- selected market rank;
- adjustment type;
- governed side;
- asset type;
- asset ID;
- current team ID;
- asset market value;
- asset as-of date;
- required value change;
- excess value;
- resulting Side A and Side B market values;
- resulting fairness gap; and
- resulting fairness classification.

Floating-point values use their exact IEEE-754 bit representation in the canonical payload.

## Stable versus cosmetic fields

Display names and team display names are deliberately excluded from the fingerprint. Renaming a player label or fantasy team does not change the trade identity when the stable asset/team IDs and governed evidence are unchanged.

Substantive proposal changes do change the fingerprint, including changes to:

- perspective;
- original or revised package asset IDs/order;
- selected asset ID/type;
- adjustment type or side;
- market evidence values or freshness date; or
- governed coordinates/policy versions.

## Cross-artifact validation

Before hashing, BF-382 requires the BF-377 envelope and BF-380 materialized artifact to agree on:

- league ID;
- season;
- source;
- minimum-as-of boundary;
- explicit perspective; and
- original Side A / Side B packages.

It also requires compatible action/materialization states. Mismatched artifacts fail closed instead of producing an identity.

## Safety boundary

BF-382 does not:

- authorize a user action;
- send negotiation wording;
- submit or execute a trade;
- create a platform request;
- refresh or recompute trade evidence;
- change the selected counter;
- change BF-369 through BF-381 semantics; or
- modify Trade Recommendation v5.

A future authorization contract may reference this fingerprint to bind approval to one exact reviewed proposal. That future authorization design is a separate product and safety boundary.
