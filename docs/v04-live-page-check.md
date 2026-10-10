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

## BF-1051 missing automatic update detection

The on-machine checker now detects a more dangerous case than a stale record: when the Dashboard has the **exact audited stale/refresh-recommended authorization** (including a valid audit ID and any BF-636 nine-step plan), or the Waiver Board has an exact authorized `data-butler-auto-waiver` marker, the page must actually include a CSP-nonced on-open refresh script. If the script is missing, the checker reports `FAIL AUTO MISSING` rather than `WARN AUDIT STALE / NOT NEEDED`. It also rejects a script with a mismatched CSP nonce. A no-transaction/manual-only state and incomplete/invalid authorization are never treated as an automatic-write entitlement. This test reads HTML only; the protected server-side POST still decides whether local evidence may be updated.

This diagnostic verifies that automatic refresh was **offered** on a qualified page, not that a browser ran it, that the POST succeeded or that provider data is fresh.

## BF-1052 honest My Team freshness language

The v0.4 My Team manager header now says **ROSTER SNAPSHOT** instead of the unconditional **UP TO DATE** badge. That previous badge was hard-coded and did not prove that Sleeper injury news, FantasyPros projections or waiver evidence had changed since the last check. A successful roster GET can describe the current assembled roster without certifying independent external feeds. This is a user-facing wording correction, **not** an added source synchronization; actual week/injury freshness still needs validation on the user's Windows app.

## BF-1053 optional real Sleeper NFL week comparison

An **optional live public-source check** now compares the application's *confirmed* matchup season/week to Sleeper's documented current NFL season/week. To run it without typing PowerShell, with an already-running v0.4 development app, double-click `scripts/butler-v04-week-check-open.cmd`. The simpler `butler-v04-live-page-check-open.cmd` remains **localhost-only** with no external network access.

The optional command performs exactly **one HTTPS GET** to `https://api.sleeper.app/v1/state/nfl` (five-second timeouts, redirects disabled, bounded 8 KB JSON). It sends **no user identity, league ID, roster, token or credential**, and does not POST. It compares the provider's regular-season `season` and `week` to the unique BF-840 confirmed matchup's `data-butler-matchup-season` attribute plus rendered `Week N` in the page header. Both season and week must agree before showing `PASS WEEK MATCH`. A different week or season yields `WARN WEEK MISMATCH`; an unavailable pairing, malformed JSON, unproved/offseason state or ambiguous header yields `WARN WEEK UNVERIFIED`; connectivity failure yields `WARN SLEEPER OFFLINE`. The check deliberately avoids raw team/player names and league metadata in its output.

This **cannot certify** that current injuries, projections, lineups or waived players match Sleeper. It is a *read-only, opt-in diagnostic* to catch regressions such as a local matchup remaining on Week 2 when Sleeper reports Week 5. It does not synchronize data, change the running app, or relax v0.3.0's frozen status. Windows fixtures cover a matching season/week, old week, old season, unconfirmed/missing metadata and malformed provider responses.

## BF-1054 automatic read-only Sleeper week proof on Matchup views

**Ordinary Matchup and Start/Sit route visits now perform a bounded read-only public provider check without asking the manager to press Refresh.** The existing BF-849 Java weekly evidence bundle launches one concurrent `SleeperClient.getNflState(Duration.ofSeconds(4))` call and emits a `WEEK_FRESHNESS` section. This checks only public Sleeper `state/nfl` (no roster/account/league ID in that call), and it adds at most a bounded wait for an external response. The existing persisted pairing is never written by the GET.

The final BF-840/BF-881 manager page displays one of three explicit states **on page load**: `WEEK MATCHES SLEEPER` means the public NFL season/week agrees with Butler's stored matchup season/week, **not** that injuries/projections or roster are updated; `SAVED MATCHUP OUTDATED` means the current public week differs and Butler withholds the saved opponent/Start-Sit pairing instead of presenting stale matchup advice; `WEEK NOT VERIFIED` means Sleeper was unreachable or its season/week payload was invalid/ambiguous, and Butler retains saved display with a prominent uncertainty warning. Saved mismatch never automatically changes the underlying week because the existing BF-840 import operation is a database write that currently needs its own governed synchronization path.

On Windows this is covered by `butler-bf1054-matchup-live-week-acceptance.ps1` (current, wrong week, duplicate/missing/contradictory proof, source unavailable) and the new Java `ButlerWeeklyMatchupLiveWeekBf1054Test` (live status parser, fail-closed payloads and no imported matchup writes). The fast Windows gate exercises the PowerShell helper and its real stage-wiring. This feature is **not** a substitute for full live league verification and does not perform player injury or projection syncing.

## BF-1055/1056 do not offer outdated Start/Sit advice

**Source mismatch is a hard stop, not a gentle alert.** When the live public Sleeper season/week differs from Butler's persisted matchup, the Matchup route now renders an advice-free hold screen. It does not show the old opponent or a `Review Lineup` call-to-action. The Start/Sit route renders an explicit `Start/Sit review held` panel, not player promotions, bench moves, or a synthetic recommendation. Start/Sit also withholds recommendations whenever the public week could not be verified, even if the saved weekly evidence was otherwise complete. A passive Matchup on an unverified week may display its saved pairing with a prominent `WEEK NOT VERIFIED` notice; the linked Start/Sit route remains blocked until live week proof is available.

The Java weekly bundle does not merely hide already-calculated advice: when a Start/Sit visit cannot prove an exact `MATCH`, it **skips the live FantasyPros/AutoFill recommendation request**, returns an explicitly unavailable review, and does not synthesize projections. When public week proof matches, the existing guarded lineup recommendation path still runs. This adds no Sleeper transactions, lineup writes or new roster mutations.

The `butler-v04-live-page-check` diagnostics now require exactly one `data-butler-week-state` marker on Matchup and Start/Sit. Missing, ambiguous or forged markers produce `FAIL WEEK PROOF`; `MISMATCH` and `UNVERIFIED` produce source-specific warnings, and held pages with actual starter-change advice produce `FAIL HELD ADVICE`. Only a real current `MATCH` can keep those pages at normal source-week status; this is **not a certification of current injuries, roster membership or projections**. Windows PowerShell 5.1 tests and real localhost HTTP smoke cover these cases, without touching a user's league.

## BF-1057 Auto-Pilot cannot approve a saved-week mismatch

A Dashboard's audited `CURRENT_AND_ACTIONABLE` decision may still refer to the **wrong saved matchup week**. Auto-Pilot therefore checks the inner **read-only Matchup GET** when the Dashboard watch would otherwise be marked Ready. That Matchup visit already performs BF-1054's timeout-bounded public Sleeper NFL season/week verification. Auto-Pilot accepts a uniquely marked `MATCH` on its actual live-week status section; it does **not** guess from a headline or a forged HTML element.

A uniquely proven `MISMATCH` changes the Auto-Pilot badge to **SAVED WEEK OUTDATED** and masks Start/Sit and Waiver actions to **DO NOT ACT**. Offline `UNVERIFIED`, failed Matchup HTTP requests, missing proof, or duplicate/ambiguous proof display **WEEK NOT VERIFIED** and mask both signals to **UNAVAILABLE**. All hold states block the prepared manager review packet. When the Dashboard decision is already stale/blocked, Auto-Pilot preserves that hold without making an unnecessary additional Matchup GET. The existing guarded Dashboard-local refresh behavior remains independent; a new NFL week is **not** silently written or synced.

This is a read-only on-open safety check, not a league update, Sleeper transaction, automatic week import, or injury/projection freshness guarantee. The extended BF-1024 Windows acceptance exercises matching, stale, unavailable, missing, forged and duplicate week markers and verifies that held Auto-Pilot HTML cannot leak old player/waiver actions. Full connected Windows/browser/Sleeper validation remains release-blocking; v0.3.0 remains frozen.

## BF-1058 strict public-week evidence parsing

The safety gates for Matchup, Start/Sit and Auto-Pilot now reject **ambiguous public NFL week evidence** instead of accepting the first plausible current-week value. The PowerShell stage requires exactly one recognized public-source heading, boundary, state, saved season/week and provider season/week field. A second invalid `State:`, `Saved season/week:` or `Provider season/week:` line is a blocker just like a duplicated valid line. A missing, renamed or duplicated proof heading/boundary also fails closed to `UNVERIFIED`.

The Java source parser rejects duplicate JSON properties (even when both values agree) and trailing JSON tokens after a valid public Sleeper `state/nfl` response. Such a malformed response may not authorize Start/Sit projections or a current Auto-Pilot watch. Regression fixtures cover these conditions. No read-only checks change the database, set weekly matchup state, or perform a Sleeper transaction. This strengthens proof integrity but **does not** certify injuries or projections; the connected user's Windows/Sleeper smoke gate remains pending.

## BF-1059/1060 guarded on-open current-week pairing recovery

The existing BF-840 weekly pairing importer now verifies **two independent Sleeper views before its first local database write**: the linked league's `in_season` status, season and provider leg, and the public NFL `state/nfl` regular-season season/week. A mismatch, invalid/duplicate JSON key, source outage or offseason state fails closed. This applies to the explicit BF-840 sync CLI too; source verification is not just UI decoration. The public check uses one read-only request and supplies no league or account identity.

For exact `/matchup` and `/matchup/autofill` visits with a uniquely rendered `SAVED MATCHUP OUTDATED` **and** an advice-free `DO NOT ACT` hold, the v0.4 worker may now attempt **one guarded Butler-local week pairing recovery while the page opens**. It checks the configured Butler league identity, external runtime DB and installed Java CLI; the BF-1059 CLI rechecks both source-week proofs before persisting. Its writer claim is exclusive with BF-723 roster-drift recovery and token-gated `POST /refresh`. It invalidates the prior Dashboard, My Team, Waiver and Matchup read caches on completion or partial failure, and retries the exact original GET once. Five-minute cooldown limits repeated failed/week-transition attempts. No automatic client-side Refresh click or JavaScript is required. If the provider status and public week disagree, the repair fails and the original `DO NOT ACT` hold remains visible rather than guessing.

**Scope/boundaries:** This updates *Butler's local weekly roster/matchup evidence only*, not Sleeper's lineup, rosters, trades, waivers, FAAB or injury/projection feeds. It does not claim that a matching week means current player projections. It does not auto-import a different season, change league configuration, or override the configured manager identity. Only exact Matchup/Start-Sit routes auto-repair in BF-1060; Dashboard/Auto-Pilot still use their existing audited checks and source-week holds. The offline Windows BF-1060 acceptance covers route proof, duplicate/forged metadata rejection, one-writer claim, retry cooldown and cache invalidation. Actual connected Windows/Sleeper browser verification must pass before v0.4 can be released or deployed. v0.3.0 stays frozen.
