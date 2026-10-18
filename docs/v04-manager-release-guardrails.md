# Matchup lineup panel release guardrail

The unified release run reached BF-844 after passing packaged launch, companion
routes, missing-database protection, concurrent reads and security. It then
rejected the passive Matchup page because it required the legacy `Lineup advisor`
label. My Team had already passed. The v0.4 renderer retains the actual lineup
panel while using Start/Sit wording; saved-review panels can retain older wording.

The Matchup guardrail now requires exactly one actual lineup recommendation
section. A sidebar label cannot satisfy it, and duplicate panels are rejected.
Existing week/pairing, read-only, desktop-surface and gambling-copy checks remain.
The Start/Sit fixture exercises this release assertion with the current panel,
legacy saved-review wording, a missing panel, duplicate panels and navigation-only
labels. This changes acceptance logic, not lineup advice, eligibility or writes.

The next focused run reached History and exposed another stale assumption:
public normalization intentionally removes technical record disclosures. A
single saved decision therefore has a View decision link without a details tag.
History acceptance now checks the exact empty state or a saved-decision link on
every card, plus collapsed older-record navigation with a matching count when
multiple records exist. Real native rendering, link insertion and public
normalization fixtures cover zero/one/multiple records and reject broken or
external links, missing/corrupt older disclosure and contradictory empty state.
