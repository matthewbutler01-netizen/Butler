# Dashboard audit presentation regression

The local Windows browser checkpoint exposed a false AUDIT UNVERIFIED warning
while the read-only CLI summary had verified current actionability and lineage.
The public Dashboard contained two exact `Decision state:` fields: the original
hidden refresh contract translated to friendly text, and the later canonical
hidden audit proof. The unchanged strict parser rejected that ambiguity.

The presentation fix requires exactly one original source field per audit label,
then removes only the translated decision-state field inside the known flat
hidden refresh contract before emitting the canonical proof. Visible copy and
the strict audit parser remain unchanged. Duplicated, conflicting, missing or
malformed source fields do not gain a canonical proof block. Multiple matching
hidden refresh contracts also leave the result unverified.

Regression evidence:
- Real presentation + unchanged parser: canonical fields unique, stale state
  preserved, duplicate/conflicting/malformed source rejected.
- Real loopback public HTTP worker fixture now uses the same hidden refresh
  contract as the production Dashboard; its wire fields remain unique.
- Existing live-page freshness and Auto-Pilot weekly-watch acceptance pass.

This is a presentation-contract repair. It does not create new audit evidence,
remove player holds, certify missing injuries/projections, or permit a Sleeper
write. The unchanged live app remains on the previously tested source pin until
new exact-SHA CI and guarded acceptance complete.
