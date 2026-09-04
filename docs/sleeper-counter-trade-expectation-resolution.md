# Sleeper counter trade expectation resolution

Policy ID: `sleeper-trade-expectation-resolution-v1-external-id-owned-assets`

## Purpose

BF-399 resolves Butler's governed internal trade coordinates into the exact Sleeper identifiers consumed by BF-398 reconciliation.

It is a local identity/ownership resolver. It does not call Sleeper and does not mutate execution state.

## Existing Butler mappings

The resolver uses existing Butler data rather than creating a parallel provider map:

- `leagues.external_id` -> Sleeper league ID;
- `teams.external_id` -> Sleeper roster ID;
- `players.external_id` -> Sleeper player ID;
- `draft_picks.original_team_id` -> original Sleeper roster through the team's external ID;
- `draft_picks.owner_team_id` -> current Butler ownership;
- `rosters(team_id, player_id)` -> current Butler player ownership.

## Side semantics

As everywhere else in the trade pipeline:

- Side A package contains assets Side A team sends;
- Side B package contains assets Side B team sends.

For a Side A player:

- Sleeper `drops[player] = sideA roster`;
- Sleeper `adds[player] = sideB roster`.

Side B is mirrored.

For a Side A draft pick:

- original roster = external ID of `original_team_id`;
- previous owner = Side A Sleeper roster;
- new owner = Side B Sleeper roster.

Side B is mirrored.

## Fail-closed requirements

Resolution is `UNAVAILABLE` when any required mapping or ownership fact cannot be proved, including:

- missing Butler league/team/player/pick;
- non-numeric Sleeper league ID;
- missing/non-positive team roster external ID;
- player missing a Sleeper external ID;
- player not currently rostered by the stated sending team;
- draft pick in another league;
- draft pick not currently owned by the stated sending team;
- original draft-pick team missing a Sleeper roster external ID;
- duplicate resolved Sleeper player or pick movement;
- both sides resolving to the same Sleeper roster.

The resolver never guesses provider IDs from Butler names.

## Output

A successful `RESOLVED` result carries one BF-398 `ExpectedTrade` with:

- Sleeper league ID;
- round/week supplied by the caller;
- exact two Sleeper roster IDs;
- player add/drop maps;
- draft-pick movement set;
- optional Sleeper creator user ID;
- governed not-before epoch-millisecond boundary.

## Non-goals

BF-399 does not:

- choose the Sleeper week/round;
- fetch Sleeper transactions;
- match transactions;
- identify the user's Sleeper user ID automatically;
- mutate BF-395/BF-396 state;
- submit a message or trade.
