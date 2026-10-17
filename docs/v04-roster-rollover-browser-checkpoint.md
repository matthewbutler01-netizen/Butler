# v0.4 guarded browser checkpoint - October 16, 2026

Running app: `cbaf8687fca950593ddd2db1836fa60be121a587`.
This records one local Windows in-app-browser session, not release approval.

## Recovery and observed behavior

- Saved an online SQLite backup and verified its integrity before recovery.
- Independently completed BF-840 current-week matchup synchronization before
  the first waiver-evidence recovery write.
- Existing exact-drift BF-723 recovery completed BF-602/603/605/606/607/612,
  BF-840, post-recovery BF-610 and BF-615/617 successfully.
- My Team and Waiver Board changed from error screens to rendered pages.
- Waiver Board displayed Previous-week waiver move - do not act / NEW WEEK -
  HOLD and a separate confirmation link.
- Followed the link and cancelled. Dashboard retained DO NOT ACT. Requested a
  fresh confirmation, confirmed once, and observed Butler refresh finished.
  The browser observation timed out while that request ran; it was not retried
  or resubmitted. The next observation showed the completed result.
- Waiver Board subsequently displayed READY FOR REVIEW with a governed pair.
  Read-only CLI summary verified CURRENT_AND_ACTIONABLE,
  LIVE_ACTIONABLE_VERIFIED and LATEST_EVIDENCE_LINEAGE_VERIFIED. New audit
  captured at 2026-10-17T02:09:40.433369300Z (October 16 in America/Chicago).
- Start/Sit rendered Week 6 and retained three player holds, incomplete projection
  coverage and no direct start/sit change ready. No lineup was submitted.
- GET-only page check: 0 page failures, 2 watch warnings (previously 3 failures).
  Matchup pairing, cross-route week/league alignment and public NFL-week match
  passed. Dashboard audit remained unverified and Auto-Pilot watch incomplete;
  neither warning is certified away by the passing page check.

## Source follow-up and limits

The source follow-up moves BF-840 inside BF-723 ahead of its waiver write stages,
so future callers do not need the independent precheck performed in this session.
The real recovery orchestration is tested with simulated CLI results: exact drift,
current roster, unrelated error, BF-840 failure and each waiver-stage failure.
A BF-840 failure must perform zero waiver stages. Provider calls and database
writes are replaced in those offline fixtures.

This session exercised the prior app pin, not the changed source SHA. New source
and packaging must pass their own exact-SHA CI and browser checks. Non-adjacent
weeks, postseason and completed/pending transaction variants were not recreated
in live data. The one-click cold-start path and latest startup-reporting changes
also need a fresh acceptance run. Live injury clearance and missing source-season
coverage in the saved explanation are not established by this test.

No Sleeper transaction was requested or submitted. v0.3 remains frozen.
Do not publish the local database backup, raw recovery log or roster screenshots.
