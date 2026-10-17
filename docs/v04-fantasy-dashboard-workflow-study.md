# A team-first dashboard for Butler

Public workflow study, 2026-10-17. This is a proposed implementation sequence,
not a claim that FantasyPros internals, paid screens or algorithms were inspected.
The accepted beta app/package pins remain unchanged by this document.

## Public observations

FantasyPros describes its My Playbook dashboard as a team-specific home screen
with matchup context, lineup/waiver/trade suggestions and relevant team insights.
Its public screenshots describe roster-aware comparisons and short player notes.
These are useful interaction patterns to implement independently in Butler.

Sources:
- https://support.fantasypros.com/hc/en-us/articles/360018746693-What-is-the-My-Playbook-Dashboard-NFL
- https://www.fantasypros.com/myplaybook/screen-shots/
- https://www.fantasypros.com/nfl/myplaybook/intro

The screenshot gallery includes older examples; use it as evidence of workflow
patterns, not proof of the current authenticated layout. This study did not
inspect an authenticated account, private endpoints or recommendation formulas.

## Butler implementation map

| Workflow | Existing Butler foundation | Proposed Butler behavior |
| --- | --- | --- |
| Team home screen | Dashboard command center and BF-993 glance presentation | Lead with this week's decisions, followed by matchup and team context. |
| Lineup suggestions | Start/Sit source proof, hold review queue and Auto-Pilot gate | Distinguish proposed manager moves from evidence holds and optimizer placements. |
| Waiver suggestions | Exact recorded add/drop pair and evidence disclosure | Show the recorded pair, coverage and review link; never infer an add from board order. |
| Player comparisons | BF-906/BF-944/BF-1000 comparison flows | Preserve the originating roster slot and return to the decision under review. |
| Player notes | BF-950/BF-951/BF-953 attributed expert merge | Show a short attributed observation with date and link, separately from Butler's conclusion. |
| Trade opportunities | BF-831 trade analyzer | Begin with manager-selected scenarios; do not label a suggested partner or trade as verified without supporting inputs. |
| Team insights | Existing roster/matchup context | Later add verified bye conflicts and roster-specific news when suitable source coverage exists. |

## First implementation slice: weekly decision queue

Use the Dashboard's existing governed inputs to render a small ordered queue:

1. Explicit do-not-act, wrong-week or missing-source conditions.
2. Verified lineup proposals, or a clearly labeled review of player holds.
3. The independently eligible exact waiver pair.
4. Optional manager-initiated comparison or trade review.

Each entry needs a plain-language reason, current week/team binding, evidence
state and a direct link to its existing review page. Include an observation or
fetch time only when the underlying input actually supplies it. A missing time
is unknown. Do not turn a hold count into a count of recommended moves.

Keep the current audit fields and source gates authoritative. Avoid another
independent parser of player recommendations. Extract a structured queue model
from existing governed results before styling it; review source/render boundaries
before choosing the exact integration point in the generated Dashboard pipeline.

The queue should summarize the existing advice, not create a second optimizer.
It should remain useful when only some tools have verified evidence. A blocked
lineup must not hide an independently eligible waiver review.

Acceptance cases for that slice:
- Complete current evidence: only the actual verified proposals are actionable.
- Partial projections/player holds: review task appears; no lineup approval packet.
- Missing or conflicting audit proof: warning and safe review path, no ready claim.
- Stale or wrong-week recommendation: visible hold, no reused previous-week move.
- Duplicate inputs: fail closed rather than choosing the first recommendation.
- Empty queue: distinguish verified no-change from insufficient evidence.
- Narrow viewport: readable reasons and links without horizontal overflow.
- GET/reload: preserve existing governed local-refresh policy and zero provider writes.

## Subsequent slices

Add the matchup overview using verified week/opponent context. Show live scoring
only if an appropriate source and freshness contract are actually available.
Then improve comparison continuity and attributed notes. Add bye-week planning
and team news after validating player identity, publication dates, source access
and roster relevance. Keep unverified coverage explicit throughout.

## Scope and data boundary

Exclude betting odds, props, parlays, sportsbook referrals, DFS, paid contests,
promotional widgets and affiliate clutter. Use Butler's own layout, copy and code.
Do not copy FantasyPros source code, branding, artwork, articles or proprietary
ranking datasets. Study visible behavior without bypassing authentication or
paywalls. Any direct FantasyPros data integration needs a separately verified
authorized interface and usage terms; none is assumed or added here.

Sleeper transactions remain disabled. Auto-Pilot stays preview-only. v0.3 stays
frozen. This study does not change the accepted v0.4 launcher or app pins.
