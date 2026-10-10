# Butler v0.4: local page/freshness smoke check (draft)

This check is for the **development v0.4 app** only. The v0.3.0 release is frozen. It does not deploy, merge, start, or stop an app, and it never submits a Sleeper transaction.

## Windows quick run

**No PowerShell typing needed:** with the v0.4 development checkout and app already running, double-click `scripts\butler-v04-live-page-check-open.cmd` in File Explorer. It launches the same GET-only smoke check, keeps the results visible, and pauses before closing. It never launches, stops, or replaces a running Butler app.

Or use this single command from Command Prompt or PowerShell:

```powershell
.\scripts\butler-v04-live-page-check.cmd -Port 18080
```

If no port is specified, the tool looks for **exact v0.4 audited-freshness Butler loopback health** on ports `18080` and `8080`. The public health response must include the `featureSet` identifier `v04-audited-onopen-freshness-bf1048`; a frozen v0.3 instance or another app does **not** qualify. It refuses to guess when both respond or neither responds. It connects only to `127.0.0.1` and only makes GET requests.

It checks Dashboard, My Team, Waiver Board, Matchup, Start/Sit, League, and Auto-Pilot. **BF-1049 checks real page content, not just sidebar labels:** My Team must show its roster section and count, Waivers its decision hero, Matchup its weekly matchup hero, Start/Sit its actual recommendation panel, and League its governed guidance. A loaded page missing those contracts is a FAIL. A My Team page without roster cards and a Matchup page without a confirmed opponent are WARN rather than falsely treated as ready. Each check inspects HTTP 200, the expected current page marker, `Cache-Control: no-store`, and the restrictive CSP. On Dashboard, Waiver Board and Auto-Pilot, `auto=ARMED` means the nonce-protected on-open refresh script is present and the CSP matches. `auto=NOT NEEDED/GATED` means no eligible automatic evidence update was announced. Neither proves browser JavaScript has executed.

If Auto-Pilot says `WATCH DATA UNAVAILABLE` or `EVIDENCE NEEDS REFRESH`, its row shows `WARN WATCH INCOMPLETE`, not a fabricated current lineup or waiver recommendation. Even complete weekly summary cards are blocked from Auto-Pilot manager approvals unless the Dashboard audit is uniquely `CURRENT_AND_ACTIONABLE` or `NO_TRANSACTION_TO_ACT_ON`. A `FAIL` is a page, network, or security-contract problem, and the command exits nonzero.

The tool reports **only route names and pass/warn/fail categories**. It does not print roster names, league identifiers, audit IDs, authentication tokens, or raw responses.

## Boundaries and remaining checks

The diagnostic itself never POSTs and never creates/cancels/submits waivers, lineups, trades, or FAAB changes. **Important:** an ordinary My Team or Start/Sit GET may independently trigger Butler's existing narrowly governed BF-723 Butler-local evidence recovery when exact roster drift is proven. That is app behavior, not a new diagnostic write endpoint.

PowerShell cannot execute the on-open browser JavaScript. After smoke checks pass, verify in an actual browser that eligible stale Dashboard/Waiver Board/Auto-Pilot pages update once without pressing Refresh, that non-eligible pages do not launch an update, and that the revised data is present after navigation. The app must be running the v0.4 development SHA being tested; these checks do not validate Sleeper provider freshness without local real-league context.

Offline Windows smoke contract fixtures: `scripts\butler-v04-live-page-check-acceptance.ps1`. PR #1512 remains draft and v0.3.0 is not changed.

## BF-1041 Auto-Pilot on-open refresh

Auto-Pilot reuses its already-loaded Dashboard HTML as the only eligibility source. It must prove the exact BF-677 Dashboard manual-refresh state, BF-629 live actionability, BF-631 lineage, and any BF-636 governed plan before emitting a client-side refresh. Merely finding a Refresh link cannot authorize an update. An eligible Auto-Pilot visit uses the same existing token-gated Butler-local `POST /refresh`, with a shared audit-based five-minute cooldown across Dashboard and Auto-Pilot. On successful POST, the browser returns to `/autopilot`. No transaction is sent to Sleeper, and no automatic write is permitted on no-transaction, ambiguous, or blocked evidence.

## BF-1043 no-click browser behavior verification

Windows CI now runs `scripts/butler-auto-refresh-browser-e2e.cjs`. Unlike a static text check, it invokes the **actual PowerShell refresh renderer** via `scripts/butler-auto-refresh-browser-fixtures.ps1`, extracts the generated nonce-protected scripts, and executes them in a mocked browser environment (using Node's built-in VM). The test confirms that eligible page load calls the same-origin token-gated POST without a click; Dashboard and Auto-Pilot share the audited five-minute session cooldown; Waivers has its own cooldown; successful responses navigate to the correct page; server rejection and network failure do not retry automatically; and a new audit ID permits a new check. No real HTTP connection is made by the fixture.

This is a browser-behavior **simulation**, not a live Chromium or real Sleeper session. Before lifting the v0.4 release hold, the user must still verify that the app renders correct roster, matchup and waiver data, and that the real browser sees the updated evidence after the governed refresh.

## BF-1050 real local HTTP diagnostic integration

CI now executes `scripts/butler-v04-live-page-check-http-e2e.cjs` as part of its **fast Windows freshness gate**. This is a transport-level test rather than a markup-only fixture: Node starts a temporary synthetic server bound to `127.0.0.1` on an ephemeral port, spawns the unchanged PowerShell 5.1 `butler-v04-live-page-check.ps1`, and inspects its real GET requests, exit codes, audit warnings, page-content checks and CSP nonce results. Cases include seven correctly rendered manager pages, stale/empty/unconfirmed data, a navigation-only My Team page, a mismatched/older v0.3 health identifier, malformed CSP, a valid nonced refresh script, and a CSP nonce mismatch. The mock rejects any POST or unknown route; the test asserts all requests are GETs. No Sleeper endpoint or real league is contacted.

The fake server is **not** the Butler Java backend. Passing BF-1050 proves the local diagnostic correctly performs HTTP checks and reports safe results; it does not prove that real Sleeper records, projections or provider injury feeds are up to date. The actual v0.4 app running on your Windows machine and connected to your league remains the release-blocking end-to-end step.
