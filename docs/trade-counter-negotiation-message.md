# Trade Counter Negotiation Message

BF-378 governs neutral, read-only negotiation wording derived from the BF-377 bound counter-proposal envelope. BF-379 exposes that governed wording through the existing read-only `trade counter-proposal` CLI surface.

Policy: `trade-counter-negotiation-message-v1-bound-neutral-first-person`

CLI:

`butler trade counter-proposal <league-id> <season> <side-a-assets> <side-b-assets> <side-a|side-b> [source] [--minimum-as-of YYYY-MM-DD]`

This policy and CLI output generate text only. They do not send a message, contact another manager, submit a trade, or mutate league state.

## Input boundary

The message policy consumes only a `TradeCounterProposalEnvelopePolicy.Envelope`.

That means any message-producing `COUNTER` has already passed:

1. live Trade Recommendation v5 evidence gates;
2. perspective-aware v5 `REJECT`;
3. market-fair single-asset candidate discovery;
4. bilateral strategic veto protection;
5. strategic eligibility;
6. unique-best candidate selection;
7. governed read-only `COUNTER` proposal construction; and
8. binding to the explicit team perspective and original Side A / Side B packages.

The message layer does not rerun or reinterpret those decisions.

## States

- `MESSAGE_AVAILABLE`
- `NO_MESSAGE`
- `INCONCLUSIVE`

Mapping is strict:

- BF-377 envelope action `COUNTER` -> `MESSAGE_AVAILABLE`;
- envelope action `NO_ACTION` -> `NO_MESSAGE`;
- envelope action `INCONCLUSIVE` -> `INCONCLUSIVE`.

Non-message states carry no actor and no text.

## Actor translation

The proposal side is translated relative to the explicit bound perspective:

- if the proposal modifies the selected perspective's own outgoing package, actor = `ME`;
- if the proposal modifies the opposite package, actor = `OTHER_MANAGER`.

No ownership or perspective is inferred from asset names or values.

## Neutral wording contract

The wording is deterministic and intentionally plain:

- own-side add: `I'd counter by adding <asset> to my side of the deal.`
- other-side add: `I'd counter if you add <asset> to your side of the deal.`
- own-side remove: `I'd counter by removing <asset> from my side of the deal.`
- other-side remove: `I'd counter if you remove <asset> from your side of the deal.`

The policy does not add persuasion, urgency, bluffing, claims about the other manager's needs, or unsupported value language.

## CLI exposure

BF-379 appends the governed message result to `trade counter-proposal` after the proposal and BF-377 envelope have been produced and verified.

The CLI prints:

- message policy provenance;
- message state and reason;
- actor when a message is available; and
- the exact governed negotiation text.

For `NO_MESSAGE` or `INCONCLUSIVE`, the CLI prints that no negotiation message is available and does not synthesize fallback wording.

The command explicitly states that the wording is read-only and is not sent by Butler.

## Safety boundary

BF-378/BF-379 do not:

- send the generated wording;
- choose a recipient or communication channel;
- submit or alter a trade;
- create alternative phrasing based on personality or negotiation tactics;
- change the selected asset or adjustment;
- change the bound team perspective or original packages;
- change BF-369 through BF-377 semantics; or
- modify Trade Recommendation v5.

Any capability that actually sends the wording requires explicit authorization semantics and remains outside this contract.
