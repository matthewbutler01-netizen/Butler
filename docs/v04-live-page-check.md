# Butler v0.4: local page/freshness smoke check (draft)

This check is for the **development v0.4 app** only. The v0.3.0 release is frozen. It does not deploy, merge, start, or stop an app, and it never submits a Sleeper transaction.

## Windows quick run

**No PowerShell typing needed:** with the v0.4 development checkout and app already running, double-click `scripts\butler-v04-live-page-check-open.cmd` in File Explorer. It launches the same GET-only smoke check, keeps the results visible, and pauses before closing. It never launches, stops, or replaces a running Butler app.

Or use this single command from Command Prompt or PowerShell:

```powershell
.\scripts\butler-v04-live-page-check.cmd -Port 18080
```

If no port is specified, the tool looks for **exact v0.4 audited-freshness Butler loopback health** on ports `18080` and `8080`. The public health response must include the `featureSet` identifier `v04-audited-onopen-freshness-bf1048`; a frozen v0.3 instance or another app does **not** qualify. It also requires the `readinessContract` value `v04-sourced-onopen-bf1098` and the new minimum worker `liveCheckContract` value `v04-current-page-proof-bf1110`. An earlier loaded v0.4 request worker will fail the health gate even if its BF-1098 marker is valid. The checker does not replace or restart the running app. It refuses to guess when both respond or neither responds. It connects only to `127.0.0.1` and only makes GET requests.

It checks Dashboard, My Team, Waiver Board, Matchup, Start/Sit, League, and Auto-Pilot. **BF-1049 checks real page content, not just sidebar labels:** My Team must show its roster section and count, Waivers its decision hero, Matchup its weekly matchup hero, Start/Sit its actual recommendation panel, and League its governed guidance. A loaded page missing those contracts is a FAIL. A My Team page without roster cards and a Matchup page without a confirmed opponent are WARN rather than falsely treated as ready. Each check inspects HTTP 200, the expected current page marker, `Cache-Control: no-store`, and the restrictive CSP. On Dashboard, Waiver Board and Auto-Pilot, `auto=ARMED` means the nonce-protected on-open refresh script is present and the CSP matches. `auto=NOT NEEDED/GATED` means no eligible automatic evidence update was announced. Neither proves browser JavaScript has executed.

If Auto-Pilot says `WATCH DATA UNAVAILABLE` or `EVIDENCE NEEDS REFRESH`, its row shows `WARN WATCH INCOMPLETE`, not a fabricated current lineup or waiver recommendation. Even complete weekly summary cards are blocked from Auto-Pilot manager approvals unless the Dashboard audit is uniquely `CURRENT_AND_ACTIONABLE` or `NO_TRANSACTION_TO_ACT_ON`. A `FAIL` is a page, network, or security-contract problem, and the command exits nonzero.

The tool reports **route names, pass/warn/fail categories, automatic-refresh eligibility, and local GET timings in milliseconds**. It does not print roster names, league identifiers, audit IDs, authentication tokens, or raw responses.

## BF-1110: reject a running older v0.4 manager worker

The v0.4 readiness checker previously accepted the fixed BF-1098 `readinessContract` marker from any earlier v0.4 worker. That was sufficient to exclude the frozen v0.3 release, but **not** sufficient to know that the currently running draft worker has the BF-1107/1108/1109 page-proof capabilities. A source checkout can move ahead while an older PowerShell worker stays loaded in a persistent runspace.

The current v0.4 worker's **GET-only /health** response now also advertises `liveCheckContract: v04-current-page-proof-bf1110`. The current one-click diagnostic requires the exact new marker *and* the existing featureSet/readinessContract/loopback identity. An older worker fails the **initial health gate before any manager-page GET**, instead of accidentally appearing certified under newer tests. It never starts, restarts, stops, updates or deploys a local process for the user.

This is a **minimum worker-capability gate**, not a per-commit hash and not a promise that every request runspace or external provider result is fresh. The user must use the updated draft checkout **and restart the draft worker through their existing safe launcher, only after the old draft instance is explicitly stopped by the user**; do not stop or replace the frozen v0.3 process. A single health response does not prove player injury status, local DB synchronization, browser JavaScript execution, or readiness to submit a lineup change. Windows offline and synthetic HTTP tests cover older/mismatched worker rejection. The Java health-source test pins the marker.

## BF-1109: League rankings cannot appear ready when evidence is empty

A modern League Hub may correctly render all its panels while explicitly withholding governed franchise rankings because current asset coverage is unavailable. The earlier page checker treated that empty state as an unconditional `PASS PAGE RESPONSE`, which could make a strict seven-route readiness run look all-green despite missing league evidence.

The diagnostic now checks the **actual mutually exclusive BF-909 franchise-board render variants**. A real `league-franchise-card` is a structurally valid board; the explicit `league-franchise-board` plus nested `empty` fallback is `WARN LEAGUE RANKINGS HOLD`; an unrecognizable/blank board is `FAIL FRANCHISE CONTENT`. A strict `-RequireReady` run treats the empty rankings WARN as a blocking exit 2, without pretending that rankings were verified. This reports local displayed evidence only; it is not proof of Sleeper data freshness or accurate player values.

PowerShell fixture and synthetic HTTP cases cover all three variants. No extra data request, roster/waiver action, refresh POST, or release update is introduced.

## BF-1108: current League Hub structural readiness check

Source review revealed a stale diagnostic assertion: the BF-1049 local smoke still demanded the retired headings `League intelligence` and `Governed guidance`, even though BF-909 replaced that layout with the **League hub**, **franchise board**, and expandable **governed league actions**. This mismatch could mark a valid modern League Hub `FAIL PAGE CONTENT`.

The diagnostic now checks the current production League Hub structure: a rendered `league-franchise-board` container, `league-governance` section, and the visible `League hub` / `View governed league actions` headings. Empty provider rankings remain an honest empty state and do not fabricate a top franchise. Sidebar-only, old placeholder, and missing-governance pages still fail the content gate. Windows PowerShell fixtures validate the exact BF-909 source markers and both legitimate and deficient pages; real localhost HTTP fixtures use the new layout. No additional provider reads, data writes, or lineup/waiver actions are introduced.

This specifically corrects a **diagnostic false positive**; it does not certify the connected League data, repair real HTTP route failures, or lift v0.4's draft hold.

## BF-1107: actionable local GET failure categories (read-only)

The first real Windows v0.4 readiness run reached the app health endpoint but reported `GET ERROR` for My Team and Waivers. That blanket label cannot distinguish a server-side HTTP 500 from a network timeout, refused connection, or diagnostic page-check failure. The revised tester now emits **status-only**, sanitized categories while retaining the exact same seven GET routes and exit/freeze rules:

- `HTTP 500`, `HTTP 503`, or another 4xx/5xx: Butler served an HTTP error on that route; investigate the development app worker/logs, **not** a browser refresh or Sleeper move.
- `GET TIMEOUT`: the page did not finish within the requested per-route deadline.
- `CONNECT FAILED`, `CONNECTION CLOSED`, `RECEIVE FAILED`, `SEND FAILED`: local transport broke. Recheck the existing running app/port before investigating provider freshness.
- `PAGE CHECK ERROR`: the GET returned, but the diagnostic could not inspect its response safely.
- `WARN AUDIT UNVERIFIED`: the page responded, but its audited Dashboard decision does not independently demonstrate a current actionable state. Never interpret this as lineup or waiver readiness.

The tester **never echoes** the error exception message, response body, roster, league, player, token, audit ID, or local filesystem path into the shareable report. It still does not send any POST, auto-start an app, deploy a release, alter Sleeper, or validate external injury/projection freshness. Offline Windows fixtures and a synthetic loopback HTTP regression cover status categorization, a 500 on `/team`, a 503 on `/waivers`, and rejection of a sensitive error response body. The actual user's route errors require a **new run of the updated test checkout** before a root cause can be assigned.

## Boundaries and remaining checks

The diagnostic itself never POSTs and never creates/cancels/submits waivers, lineups, trades, or FAAB changes. **Important:** an ordinary My Team or Start/Sit GET may independently trigger Butler's existing narrowly governed BF-723 Butler-local evidence recovery when exact roster drift is proven. That is app behavior, not a new diagnostic write endpoint.

PowerShell cannot execute the on-open browser JavaScript. After smoke checks pass, verify in an actual browser that eligible stale Dashboard/Waiver Board/Auto-Pilot pages update once without pressing Refresh, that non-eligible pages do not launch an update, and that the revised data is present after navigation. The app must be running the v0.4 development SHA being tested; local smoke alone does not verify the external provider or freshness of player availability.

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

The optional command performs exactly **one HTTPS GET** to `https://api.sleeper.app/v1/state/nfl` (five-second timeouts, redirects disabled, bounded 8 KB JSON). It sends **no user identity, league ID, roster, token or credential**, and does not POST. It compares the provider's regular-season `season` and `week` to **both** the unique, locally source-MATCH verified season/week metadata and its consistent saved matchup header. A matching title alone does not establish verified current-week data. Both season and week must agree before showing `PASS WEEK MATCH`. A different week or season yields `WARN WEEK MISMATCH`; an unavailable pairing, malformed JSON, unproved/offseason state or ambiguous header yields `WARN WEEK UNVERIFIED`; connectivity failure yields `WARN SLEEPER OFFLINE`. The check deliberately avoids raw team/player names and league metadata in its output.

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

## BF-1061 Auto-Pilot on-open week repair and BF-1062 exact paired-opponent proof

Auto-Pilot now performs the read-only Matchup source-week check on an ordinary `/autopilot` visit **even if the Dashboard audit is already stale**. If a unique, advice-free saved-week mismatch is proven, the worker shares BF-1060's same one-writer governance and five-minute cooldown to attempt only a Butler-local current matchup sync. A completed sync is never enough to label the watch ready: Auto-Pilot must reread Dashboard **and** Matchup directly from the running core and independently pass the audited Dashboard decision lineage and strict Sleeper public-week checks. An incomplete refresh, failed provider read, partial local sync, or concurrent writer leaves manager actions held. No manual Refresh click is required to attempt this correction.

Public season/week agreement alone cannot establish that Butler has this week's exact opponent. A uniquely rendered Matchup header must also prove the *same* season and week as the numeric, source-verified public NFL week banner. Missing/unconfirmed opponent, wrong saved pairing, duplicate metadata, and forged page proof now block prepared Auto-Pilot lineup/waiver action. No MATCH evidence is inferred from an opponent name, user-supplied text or a successful dashboard card alone. Numeric season/week metadata is emitted only when the public NFL proof is internally consistent. BF-1024/BF-1054/BF-1061 synthetic Windows checks cover positive and fail-closed paths.

Neither change refreshes player injuries or projections, guarantees the timing of a future provider update, or writes to Sleeper. The frozen v0.3.0 release is untouched, and the real Windows/Sleeper league browser gate remains required before releasing v0.4.

## BF-1063/1064 availability fail-closed safety for Start/Sit

In addition to current-week and saved-opponent verification, Butler's Start/Sit recommendation now blocks an **otherwise projected lineup swap when the live Sleeper player injury/status fetch fails**. Prior behavior held individual missing-projection players but could still promote other players using projections without an available injury/status check. BF-1063 returns an explicit held recommendation rather than treating those point estimates as injury clearance. Unchanged partial reviews remain inspectable when other independent evidence exists, but a new swap cannot be prepared on a failed availability feed.

BF-1064 also rejects duplicate top-level Sleeper player IDs, conflicting duplicate player status/injury fields, and trailing JSON tokens in the read-only player feed; previously an ordinary parser could silently accept the last conflicting status. This is essential to fail closed on contradictory `Out` and `Active` evidence rather than trusting one value. Java regression fixtures now cover those cases alongside an otherwise appealing bench promotion when the feed is unavailable.

This neither makes a projection authoritative nor verifies an individual player's health; it only prevents two identified ways in which a stale/unavailable or contradictory source could falsely authorize a manager recommendation. No Sleeper lineup/roster/waiver/FAAB write is introduced. Real connected Windows/Sleeper testing remains outstanding.

## BF-1065-1067 player-status integrity and visible Start/Sit proof

BF-1065 treats a missing/unknown generic Sleeper player `status` as a review hold rather than healthy clearance and refuses to use an availability cache if the system clock has rolled backward. BF-1066 requires an exact current Sleeper player-status entry for **both** participants in any proposed starter/bench change; the current participant is omitted only for an actual empty slot. Recognized `Active` and source-confirmed unavailable statuses can be evaluated, but partial player-map coverage or a contradictory player ID never substitutes projections for injury evidence. The source-confirmed unavailable starter replacement remains possible; uncertain replacements are withheld. An actual injury-source outage is still blocked by BF-1063.

BF-1067 adds a plainly visible, escaped, accessible note within the Start/Sit summary. It distinguishes a **proposed move backed by exact status checks at review generation** from **no change ready** and reports the count of player holds plus full/partial scoreable projection coverage. The warning explicitly states that projections do not clear players medically, status can change before kickoff, and the proposal has not been sent to Sleeper. Conservative text is also used when source data or coverage is incomplete. Dark/mobile contrast uses the existing Butler palette and dedicated panel styling. This is a presentation of the BF-1063-1066 evidence gates, not a new health/injury polling endpoint.

Windows BF-1067 acceptance tests run in the fast v0.4 freshness job and exercise proposal/no-change and partial/full evidence cases, hold counts, escaped accessible markup and no-provider-write boundaries. The app still requires final real connected Windows/Sleeper integration verification before release; **v0.3.0 remains frozen**.


## BF-1077–1079: one-click **strict real-league readiness** (Windows)

For the most useful test of your actual v0.4 development checkout, **first have the v0.4 app running**, then double-click `scripts\butler-v04-real-league-readiness-open.cmd` in File Explorer. No PowerShell commands need to be typed. It identifies the v0.4 instance via its versioned health response; an existing frozen v0.3 instance is rejected. This is *not* a launcher or installer and does not automatically deploy, launch or stop Butler.

Unlike the older quick smoke, this one-click test enables **both** `-CheckSleeperWeek` and `-RequireReady`:
- It locally reads the seven manager routes, checks page-specific response and security contracts, compares the actual source-verified Matchup and Start/Sit league/week, and measures each GET in milliseconds.
- It makes one additional read-only public HTTPS GET to Sleeper's NFL week state, and only reports `WEEK MATCH` when the app's own unique current source week, saved matchup title and Sleeper's public NFL week all agree.
- It **also checks the real Start/Sit decision-provenance note**, not just that a recommendation panel exists. A changed-lineup preview requires exact player-status proof with a fetch time within 10 minutes, projection fetch within 120 minutes, full projection coverage, zero player holds, and no unverified evidence. A legitimate no-change view can pass without needing a change-specific status timestamp, but still needs full scoreable projections, zero player holds and recent projection evidence.
- It returns **exit 0 for all checked evidence PASS**, **exit 2 for any warning** (e.g. stale/held/unverified source data) and **exit 1 for any hard error**. A warning is *not* a green readiness signal. Ordinary quick smoke without `-RequireReady` keeps its existing informational WARN behavior.

The report is saved to `%LOCALAPPDATA%\Butler\diagnostics\v04-real-league-readiness-latest.txt`, printed in the command window and opened in Notepad. It contains only categorized results, route names and timings, not player names or tokens. **Do not treat a passing check as proof that your web browser completed an automatic refresh, that lineup changes were made, or that player injuries were medically cleared.** Actual browser navigation and your real league remain release-blocking checks. The local GETs can trigger the running app's already-governed Butler-local roster/week evidence recovery; there is no new write capability and no Sleeper transaction.

Windows CI covers this with direct PowerShell fixtures and a synthetic localhost HTTP server, including missing Start/Sit proofs, stale or unverified timestamps, player holds, incomplete projections, a verified no-change decision, mismatched weeks, held Auto-Pilot and exact exit-code behavior. Those isolated tests are useful but are **not** a live real-league acceptance run.

## BF-1092/1093: verified Auto-Pilot badge and one-click packet consistency

A Dashboard `Ready` flag cannot itself make the **Auto-Pilot watch header green**. The actual Auto-Pilot renderer now shows `CURRENT SNAPSHOT` only after the independent Start/Sit source review has produced an accepted `VERIFIED_CHANGE` or `VERIFIED_NO_CHANGE` outcome. Unknown, missing, contradictory, or unsigned lineup signals display a warning instead of green, even when the surrounding Dashboard decision is current. A verified `NO CHANGE` means **keep the lineup and recheck injuries**, not a proposed Sleeper transaction.

The one-click Windows diagnostic now checks **both** the actual watch-header badge **and** the matching prepared-review badge. A green weekly watch paired with a missing, duplicate, blocked, decorative, or malformed prepared review reports `WARN WATCH INCOMPLETE`; strict `-RequireReady` exits 2. Only a single correctly rendered green `READY FOR MANAGER REVIEW` or `NO ACTION REQUIRED` packet can accompany `WATCH CURRENT`. Windows fixture tests and synthetic loopback HTTP scenarios cover these conditions.

**Scope:** this checks Butler's page contracts and aligned source-verification outcome; a synthetic HTML response can imitate a valid badge, so the diagnostic does **not** establish real external injury freshness, JavaScript on-open execution, or an actual league's health. The user's running v0.4 app/browser/Sleeper account still require independent manual acceptance. v0.3.0 remains frozen; no Sleeper write or release action was added.

## BF-1098: verify the development instance, not just its old v0.4 label

The health endpoint now exposes a distinct `readinessContract` for the current cross-route Start/Sit proof, on-open entitlement, and strict diagnostics. The one-click local check requires both the original v0.4 feature-set label and the newer readiness contract. An older v0.3 or pre-BF-1098 v0.4 app is refused even if it is healthy and occupies port 18080 or 8080. This stops an earlier worktree being mistaken for the latest draft.

Windows PowerShell fixtures and loopback HTTP checks cover current identity, pre-BF-1098 v0.4 without a contract, wrong contract, old v0.3 and public bind rejection. This marker is a compatibility test, not cryptographic proof of an exact Git commit or of fresh player injuries. Use the updated v0.4 checkout for connected league tests. The diagnostic never starts, stops, installs or upgrades the app; v0.3 remains frozen.

## BF-1099: visible source status on Auto-Pilot

The Auto-Pilot manager page now surfaces lineup evidence as a full-width, readable status panel directly beneath the weekly watch. It distinguishes `SOURCED LINEUP CHANGE`, `SOURCED NO CHANGE`, and `SOURCE CHECK REQUIRED`, shows the actual retrieved projection/status timestamps when independently verified, and links directly to Start/Sit Assistant. The source panel states explicitly that a fetch timestamp does not certify that a player is healthy or eligible. Held source states show no invented verification times, and a sourced no-change decision does not claim that swap-specific player status was checked.

This is a visible user-experience change, not a new provider request or background job. It leaves the existing read-only Auto-Pilot approval boundary intact. Windows acceptance checks the rendered source panel for changed, no-change and held states and prevents a write-capable control. Verify the panel's legibility in the actual user's browser, alongside the one-click real-league smoke; the v0.3.0 release remains frozen.

## BF-1100: understand exactly why Auto-Pilot is holding a lineup

The manager-facing Lineup Evidence panel now tells the user what the app can **actually verify**, rather than presenting one generic warning. It uses only the existing audited watch state, and gives a safe navigation action for each situation: stale Dashboard evidence -> `Check Dashboard freshness`; verified week mismatch or unverified pairing -> `Check weekly matchup`; unavailable manager snapshot -> `Open Dashboard`; source-specific Start/Sit hold -> `Review Start/Sit evidence`. For a sourced change or sourced no-change decision, it explains why the panel is green and still requires a manager to review status before kickoff. The panel exposes a stable non-private `data-butler-lineup-reason` marker to support regression checks. The link is navigation-only; it does not trigger a Sleeper lineup update.

Windows renderer acceptance now covers all five held/unknown cases as well as both verified outcomes and ensures that the wrong green source labels never leak onto held pages. This does not fabricate player-specific injury reasons, start a polling service, or substitute for real local Sleeper verification. v0.3.0 stays frozen.

## BF-1101: explain a failed optional Start/Sit read without a false green watch

When Auto-Pilot opens on an otherwise valid weekly snapshot, it performs one bounded, optional read of Start/Sit evidence. If that read exceeds the existing **12-second limit**, the Lineup Evidence panel now explicitly reports the timeout, keeps lineup advice held, and offers a navigation-only link to review Start/Sit. A non-200 result, unusable response, or other read failure instead reports that the source page was unavailable. The error class comes from the bounded local request, not a guessed player injury or a provider publication time.

The existing source-validation and waiver boundaries remain intact. A failed optional Start/Sit read never claims a verified player status, cancels a waiver decision, starts a repeated retry loop, or reveals exceptions, tokens, player names, league IDs, or raw responses. Windows acceptance checks both failure categories and ensures a transport annotation cannot override an independently verified source result. This is clearer local failure feedback, **not** proof of real provider freshness or a new data feed. No Sleeper transaction is submitted.

## BF-1102: show exactly which NFL week Auto-Pilot verified

The Auto-Pilot weekly watch now visibly prints `NFL <season> season - Week <N> verified against the public Sleeper week` only when the ordinary Matchup page independently produced a unique `MATCH` source state with the saved pairing season and week aligned. No host-clock guess and no stale saved-week fallback can display a confirmed week. If that independent current-week evidence is absent, stale, duplicated, or contradictory, the watch instead explicitly says that the NFL week is not independently verified. This week label is distinct from the separate injury/projection proof: a confirmed current week does **not** certify that players are healthy or that Start/Sit is ready.

The loopback readiness check also compares the already-fetched Auto-Pilot week label with the Matchup source when both pages individually verify. It reports `WATCH WEEK ALIGNED` for a match, `WATCH WEEK DRIFT` for a disagreement, and `WATCH WEEK MISSING` for absent/duplicate labels, and blocks the strict readiness gate on drift. Windows PowerShell acceptance, synthetic real-HTTP integration and manager-renderer tests cover missing, stale, duplicate, incorrect-season and mismatched-week cases. Nothing is sent to Sleeper, and v0.3.0 remains frozen.

## BF-1103: recover automatically on a later visit after a failed local refresh

The actual nonce-locked Dashboard, Waivers and Auto-Pilot on-open scripts previously started one update and immediately stored a **five-minute** cooldown, including when the local HTTP request failed or the network was unavailable. A user could return to a still-stale page and get told it was already checked, without any successful update. On a successful POST, Butler still keeps the existing five-minute shared-audit cooldown to prevent redirect/reload loops. On a failed POST or pending request, the browser now marks a distinct **one-minute** failure/pending window. After that window expires, a **new visit** can automatically try the existing governed local refresh again without pressing Refresh. It does not poll or retry during the same visit.

The implementation claims the in-flight session lock **before** issuing the one permitted same-origin token-gated `POST /refresh`, so navigating between Dashboard and Auto-Pilot or opening multiple tabs does not start duplicate concurrent POSTs. It reuses existing audit IDs and conservative server-side preflight. Legacy browser sessionStorage entries containing plain timestamps remain treated as successful five-minute locks. The isolated real-rendered JavaScript VM covers success, rejection, transport failure, retry after one minute, pending-request protection, audit rotation, waiver independence, and old-key migration. There is still no automatic Sleeper transaction; only governed Butler-local evidence recovery is eligible.

## BF-1104: clickless recovery when Start/Sit opens on a source-blocked page

An exact `GET /matchup/autofill` continues to retrieve fresh read-only lineup evidence on every visit, without serving a cached manager-page recommendation. If that page still contains exactly one verified `start-sit-assistant` section and one `start-sit-blocker`, the outer app makes **one optional 12-second-local-read-budget GET** of the Dashboard. Only an independently confirmed stale/refresh-ready Dashboard decision with the existing exact governed eligibility, nine-step-policy guard when applicable, valid audit identifier, one-use token, and nonce can arm an automatic Butler-local evidence refresh. Healthy Start/Sit pages or malformed/duplicate blockers do not trigger that second read or any script.

This is **not** an automatic lineup transaction. The on-open browser script uses the existing same-origin token-gated `POST /refresh`; the existing server preflight may still reject it. On success it reloads Start/Sit directly; on failure it shows the governed recovery message and allows one later-visit check after the BF-1103 one-minute failure lock. Dashboard, Auto-Pilot, and Start/Sit share the exact same audit success/failure key, preventing repeated same-tab refreshes between those three manager views. An unavailable Dashboard cannot arm the browser script, and the original blocked lineup page is preserved.

Windows PowerShell acceptance verifies exact-blocker eligibility and rejects unverified, duplicate, malformed and out-of-route source/dash evidence. The rendered-script VM verifies actual generated JavaScript behavior, shared audit cooldown, and deferred retries; the real Windows Chromium test verifies nonce/CSP, one localhost refresh request, redirect to Start/Sit, healthy-page no-POST, and Start/Sit-to-Auto-Pilot navigation without a second POST. It does not claim connected live Sleeper results. v0.3.0 is unchanged.

## BF-1105: make actual Start/Sit evidence readable while reviewing a lineup

The Start/Sit Assistant now places its existing, unique escaped `butler-startsit-source-proof` paragraph inside a dedicated **EVIDENCE BEHIND THIS ADVICE** block, rather than leaving it as muted microcopy inside the decision summary. That proof continues to state the actual source fetch observations and coverage/holds, and remains the exact evidence consumed by Auto-Pilot and localhost checks. A separate warning clarifies that a data-retrieval timestamp is **not injury clearance** and the manager must confirm player availability before kickoff.

The refreshed responsive style uses an explicit readable foreground, larger 14px source text and swap comparisons, 12px lineup slot labels, and 14px review-queue items, with text wrapping on narrow screens. This is presentation-only: no new provider calls, injury-state inference, refresh operation, optimizer changes or Sleeper lineup transactions. Windows acceptance requires the escaped proof to remain unique, verifies the dedicated labeled wrapper and minimum text sizes, and retains the existing source-age/source-validation gates. Actual local browser and league verification remain necessary. v0.3.0 remains frozen.

## BF-1106: actual Start/Sit proposal count in the Auto-Pilot decision

When the separate read-only Start/Sit source returns a single, current-league/week, zero-hold, full-coverage **verified-change** proof, the app carries its unique positive proposed-lineup-change count into the Auto-Pilot weekly watch. The visible **LINEUP EVIDENCE** panel and manager review queue then name the actual number of sourced lineup changes requiring review, rather than only repeating the Dashboard card's generic Start/Sit signal. The manager watch card also links straight to the current Start/Sit recommendations.

The count comes only from the same exact source proof already checked against a recent player-status observation (10-minute window) and projection observation (120-minute window); Dashboard START/SIT counts are not substituted. A missing, duplicated, contradictory, stale, zero, or player-held source never creates a verified proposal count or review claim. For a verified no-change decision, no change count or swap-status lookup is implied. Counts above 99 fall back to generic text rather than presenting an implausible number. No provider request, lineup submission, waiver claim, trade, or FAAB action is added. The previously frozen v0.3.0 remains untouched.

Windows-source gate and renderer acceptance now cover single/multiple verified proposal counts, actual manager-facing text, one-click navigation, and rejection of held/ambiguous source counts. This is a draft v0.4 improvement; it is not yet verification of any particular connected Sleeper player's eligibility.
