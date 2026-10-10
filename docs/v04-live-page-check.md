# Butler v0.4: local page/freshness smoke check (draft)

This check is for the **development v0.4 app** only. The v0.3.0 release is frozen. It does not deploy, merge, start, or stop an app, and it never submits a Sleeper transaction.

## Windows quick run

With the v0.4 development checkout and app already running, double-click `scripts\\butler-v04-live-page-check.cmd`. In a terminal you can choose the exact port instead:

```powershell
.\scripts\butler-v04-live-page-check.cmd -Port 18080
```

If no port is specified, the tool looks for **exact Butler loopback health** on ports `18080` and `8080`. It refuses to guess when both respond or neither responds. It connects only to `127.0.0.1` and only makes GET requests.

It checks Dashboard, My Team, Waiver Board, Matchup, Start/Sit, League, and Auto-Pilot. Each check inspects HTTP 200, the expected current page marker, `Cache-Control: no-store`, and the restrictive CSP. On Dashboard and Waiver Board, `auto=ARMED` means the nonce-protected on-open refresh script is present and the CSP matches. `auto=NOT NEEDED/GATED` means no eligible automatic evidence update was announced. Neither proves browser JavaScript has executed.

If Auto-Pilot says `WATCH DATA UNAVAILABLE`, its row shows `WARN WATCH INCOMPLETE`, not a fabricated current lineup or waiver recommendation. A `FAIL` is a page, network, or security-contract problem, and the command exits nonzero.

The tool reports **only route names and pass/warn/fail categories**. It does not print roster names, league identifiers, audit IDs, authentication tokens, or raw responses.

## Boundaries and remaining checks

The diagnostic itself never POSTs and never creates/cancels/submits waivers, lineups, trades, or FAAB changes. **Important:** an ordinary My Team or Start/Sit GET may independently trigger Butler's existing narrowly governed BF-723 Butler-local evidence recovery when exact roster drift is proven. That is app behavior, not a new diagnostic write endpoint.

PowerShell cannot execute the on-open browser JavaScript. After smoke checks pass, verify in an actual browser that eligible stale Dashboard/Waiver Board pages update once without pressing Refresh, that non-eligible pages do not launch an update, and that the revised data is present after navigation. The app must be running the v0.4 development SHA being tested; these checks do not validate Sleeper provider freshness without local real-league context.

Offline Windows smoke contract fixtures: `scripts\\butler-v04-live-page-check-acceptance.ps1`. PR #1512 remains draft and v0.3.0 is not changed.
