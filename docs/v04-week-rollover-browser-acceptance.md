# v0.4 guarded Windows browser acceptance

Development test candidate only. v0.3 remains frozen. Auto-Pilot is preview only.
Do not submit a Sleeper lineup, waiver, trade or FAAB change during this session.

## Before opening the beta

- App PR #1535 target (stacked on #1533): `71af533793bfc603a938125b4ffaf44904120996`.
- Source ref: `refs/heads/bf-v04-fantasy-workflow-study`.
- Require Java Build and the complete Windows PowerShell Compatibility workflow
  to pass on that exact app SHA AND the exact packaging PR #1534 head used for
  this archive. A green freshness job alone does not clear this gate.
- Record both SHAs and workflow URLs below. If either is pending, failed,
  cancelled, absent, or belongs to another SHA, leave this session NOT RUN.
- Existing clean development checkout: `C:\ButlerDev\pr1464`. If it has local
  changes, stop and preserve them. Do not reset or clean it.
- Close your known v0.4 app window with Ctrl+C. Leave v0.3 alone. If port 18080
  remains occupied by an unknown worker, stop; do not kill that process.

## One-click start

1. Use File Explorer **Extract All** and keep all package files together.
2. Double-click `butler-v04-beta-one-click.cmd`. No PowerShell typing required.
3. Runtime preparation can take several minutes. The launcher waits at most
   five minutes, prints progress and saves PENDING/HELD startup status if no
   healthy service is verified. A timeout does not stop the app window; do not
   start another copy. Inspect the known window and close it normally if needed.
4. Check the report opened in Notepad: `BETA SOURCE CHECKOUT: VERIFIED` must
   contain the full app SHA above. This proves disk checkout only.
5. Keep the new Butler app window open. Use `http://127.0.0.1:18080/`.
   A health response or passing page check does not prove browser acceptance.

## Browser checks (record PASS / FAIL / NOT EXERCISED)

1. Dashboard, My Team, Start/Sit and Waiver Board load without an error page.
   Check displayed team/roster and source timestamps. Missing/stale evidence
   must remain visibly held; do not interpret it as a recommended action.
   Dashboard shows one weekly decision queue with review links. A held lineup
   must not hide independently eligible recorded waiver review. Missing or
   duplicated audit proof must withhold the whole recommendation view.
2. With an existing saved previous-week waiver decision and independently
   verified immediately following regular-season week in the same season,
   Waiver Board shows `NEW WEEK` / `HOLD` and the previous move as history only.
   Merely opening or reloading must not execute the rollover recheck.
3. Follow **Recheck current-week waiver evidence**. Confirm that a separate
   confirmation is required. Cancel first: no recheck should execute.
4. If proceeding, request a new confirmation and confirm once. This authorizes
   Butler-local governed evidence generation only. Current-week matchup proof
   must precede evidence writes. A failed stage must stop the run and retain a
   hold. Never reuse the previous-week add/drop as fresh advice.
5. Inspect the outcome and reload Waiver Board. Record the new decision week,
   evidence timestamp and outcome. A HOLD/no recommendation is valid when
   current evidence is insufficient; it is not a successful fresh recommendation.
6. Larger week jumps, wrong season/postseason, unknown lineage or completed/
   pending transaction states must not offer this rollover capability. Mark
   any unavailable scenario NOT EXERCISED; do not change live state to fake it.
7. Auto-Pilot remains preview-only. No automatic rollover refresh or provider
   write occurs. Do not click a Sleeper submission control.
   When the matching current-week Start/Sit page reports partial projections
   and positive player holds, Auto-Pilot should explain both observed gaps.
   It must still show no prepared lineup swap and must not claim independently
   verified source freshness. If those source conditions are absent, record
   this scenario NOT EXERCISED; do not manufacture holds in the live database.

Use synthetic automated fixtures for states unavailable in the owner session;
do not claim those fixtures are real-browser or live-provider proof.

## Session record

- Packaging SHA / archive workflow URL:
- App SHA / Java workflow URL / complete Windows workflow URL:
- Local date/time, browser/version:
- Source checkout report result:
- Checks 1-7 and observed outcome:
- Rollover old week / independently verified current week:
- New evidence timestamp / decision week / HOLD or governed outcome:
- Sleeper writes: NONE (any suspected write stops acceptance).
- Overall: NOT RUN / BLOCKED / PASS / FAIL.

Do not share raw rosters, tokens, private database contents or browser payloads.
On failure, retain the local report and record only the failing check and safe
error label. Stop your known beta app normally with Ctrl+C. Do not merge,
release, replace v0.3 or mark acceptance complete from this checklist alone.
