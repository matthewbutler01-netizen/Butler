# Auto-Pilot observed evidence gaps

The current-week Start/Sit page can report partial projection coverage and
player holds while Auto-Pilot correctly withholds its lineup packet. Previously
Auto-Pilot explained this only as a generic source check failure.

The held card now reports those two observed gaps when exactly one source-proof
paragraph on matching season/week/league pages contains one positive hold count,
one recognized partial-coverage statement and no conflicting full-coverage claim.
It explicitly says source freshness is not independently verified and links to
Start/Sit for review. It does not repeat player details or infer injury causes.

This observation never sets a verified outcome, a source timestamp, a prepared
swap or approval. The existing full-coverage, zero-hold and timestamp gates remain
unchanged. Ambiguous or mismatched evidence retains the generic explanation.
The observation is cleared on each source evaluation to avoid stale reasons.
Independent waiver review and the automation-off boundary remain unchanged.

Validation: `butler-bf1074-autopilot-sourced-startsit-acceptance.ps1` exercises
valid partial holds, the blocked no-change page, duplicate proof/counts,
conflicting coverage, mismatched weeks and reused-state cleanup. Existing
prepared-packet and real-render variant suites also pass locally.

Guarded browser acceptance on the pinned candidate must confirm the held card
shows the observed gaps only when Start/Sit actually reports both, with no ready
packet or verified source-fetch claim. An incomplete watch remains a warning.
