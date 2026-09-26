param(
    [Parameter(Mandatory = $true)]
    [System.Net.Sockets.TcpClient]$Client,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$LeagueId,

    [Parameter(Mandatory = $true)]
    [int]$InnerPort,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$TradeHost,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$TradeLab,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$History,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Detail,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DecisionRefresh,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DecisionRefreshRunner,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$RepoRoot,

    [Parameter(Mandatory = $true)]
    [hashtable]$RefreshState
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$cacheName = 'ButlerBf765PublicRequestWorkerScriptBlock'
$worker = Get-Variable -Name $cacheName -Scope Global -ValueOnly -ErrorAction SilentlyContinue
if ($null -eq $worker) {
    $implementationPath = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
    if (-not (Test-Path -LiteralPath $implementationPath -PathType Leaf)) {
        try { $Client.Close() } catch {}
        throw "BF-765 BLOCKED: public request-worker implementation is unavailable at $implementationPath"
    }

    $implementation = [System.IO.File]::ReadAllText($implementationPath, [System.Text.Encoding]::ASCII)
    if ([string]::IsNullOrWhiteSpace($implementation)) {
        try { $Client.Close() } catch {}
        throw 'BF-765 BLOCKED: public request-worker implementation is empty.'
    }

    # BF-766 preserves the historical worker source and its health fast path.
    # Only the parsed per-request execution copy is changed: UI modules are
    # parsed once per persistent runspace and then dot-sourced from cached
    # ScriptBlocks. BF-767 route-scopes that cache use: exact core proxied reads
    # need only History and DecisionRefresh, while every other non-health route
    # retains the historical five-module execution order.
    $moduleLoadPattern = '(?m)^    \. \$TradeHost\r?\n    \. \$TradeLab\r?\n    \. \$History\r?\n    \. \$Detail\r?\n    \. \$DecisionRefresh\r?$'
    $moduleLoadMatches = [regex]::Matches($implementation, $moduleLoadPattern)
    if ($moduleLoadMatches.Count -ne 1) {
        try { $Client.Close() } catch {}
        throw "BF-766 BLOCKED: public UI module load block count was $($moduleLoadMatches.Count), expected exactly 1."
    }

    $moduleLoadReplacement = @'
    # BF-789 keeps companion reads on the same prebuilt Java runtime as the
    # packaged app. The release Gradle shim remains fail-closed and is never
    # widened to authorize Trade Lab or History tasks.
    $gradle = Join-Path $RepoRoot 'scripts\butler-companion-read-proxy.cmd'
    if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
        throw "BF-789 BLOCKED: companion read proxy is unavailable at $gradle"
    }

    $bf767CoreRead = $requestTarget -ceq '/' -or
        $requestTarget -ceq '/team' -or
        $requestTarget -ceq '/waivers' -or
        $requestTarget -ceq '/league'

    $bf766ModuleSpecs = if ($bf767CoreRead) {
        @(
            [pscustomobject]@{ Name = 'History'; Path = $History; CacheName = 'ButlerBf766HistoryScriptBlock' },
            [pscustomobject]@{ Name = 'DecisionRefresh'; Path = $DecisionRefresh; CacheName = 'ButlerBf766DecisionRefreshScriptBlock' }
        )
    }
    else {
        @(
            [pscustomobject]@{ Name = 'TradeHost'; Path = $TradeHost; CacheName = 'ButlerBf766TradeHostScriptBlock' },
            [pscustomobject]@{ Name = 'TradeLab'; Path = $TradeLab; CacheName = 'ButlerBf766TradeLabScriptBlock' },
            [pscustomobject]@{ Name = 'History'; Path = $History; CacheName = 'ButlerBf766HistoryScriptBlock' },
            [pscustomobject]@{ Name = 'Detail'; Path = $Detail; CacheName = 'ButlerBf766DetailScriptBlock' },
            [pscustomobject]@{ Name = 'DecisionRefresh'; Path = $DecisionRefresh; CacheName = 'ButlerBf766DecisionRefreshScriptBlock' }
        )
    }

    foreach ($bf766ModuleSpec in $bf766ModuleSpecs) {
        $bf766PathCacheName = $bf766ModuleSpec.CacheName + 'Path'
        $bf766Module = Get-Variable -Name $bf766ModuleSpec.CacheName -Scope Global -ValueOnly -ErrorAction SilentlyContinue
        $bf766CachedPath = Get-Variable -Name $bf766PathCacheName -Scope Global -ValueOnly -ErrorAction SilentlyContinue

        if ($null -eq $bf766Module -and $null -eq $bf766CachedPath) {
            if (-not (Test-Path -LiteralPath $bf766ModuleSpec.Path -PathType Leaf)) {
                throw "BF-766 BLOCKED: $($bf766ModuleSpec.Name) module is unavailable at $($bf766ModuleSpec.Path)"
            }
            $bf766Source = [System.IO.File]::ReadAllText($bf766ModuleSpec.Path, [System.Text.Encoding]::ASCII)
            if ([string]::IsNullOrWhiteSpace($bf766Source)) {
                throw "BF-766 BLOCKED: $($bf766ModuleSpec.Name) module is empty."
            }
            try {
                $bf766Module = [scriptblock]::Create($bf766Source)
            }
            catch {
                throw "BF-766 BLOCKED: $($bf766ModuleSpec.Name) module could not be parsed: $($_.Exception.Message)"
            }
            Set-Variable -Name $bf766ModuleSpec.CacheName -Scope Global -Value $bf766Module
            Set-Variable -Name $bf766PathCacheName -Scope Global -Value ([string]$bf766ModuleSpec.Path)
        }
        elseif ($null -eq $bf766Module -or
                $null -eq $bf766CachedPath -or
                $bf766Module -isnot [scriptblock] -or
                [string]$bf766CachedPath -cne [string]$bf766ModuleSpec.Path) {
            throw "BF-766 BLOCKED: cached $($bf766ModuleSpec.Name) module state is invalid or belongs to a different path."
        }

        . $bf766Module
    }
'@

    $moduleLoadMatch = $moduleLoadMatches[0]
    $implementation = $implementation.Substring(0, $moduleLoadMatch.Index) +
        $moduleLoadReplacement +
        $implementation.Substring($moduleLoadMatch.Index + $moduleLoadMatch.Length)

    # BF-791 creates one presentation boundary for every public HTML route.
    # Internal state, audit data, and service contracts remain unchanged; only
    # rendered HTML is translated into user-facing fantasy-football language.
    $sendMarker = 'function Send-HttpResponse {'
    $sendMarkerMatches = [regex]::Matches($implementation, [regex]::Escape($sendMarker))
    if ($sendMarkerMatches.Count -ne 1) {
        try { $Client.Close() } catch {}
        throw "BF-791 BLOCKED: public response boundary count was $($sendMarkerMatches.Count), expected exactly 1."
    }

    $presentationFunctions = @'
function ConvertTo-ButlerDisplayToken {
    param([Parameter(Mandatory = $true)][string]$Value)

    $labels = @{
        'HISTORY_INTEGRITY_VERIFIED' = 'Verified'
        'LINEAGE_AND_IDENTITY_VERIFIED' = 'Verified'
        'NO_HISTORICAL_FINALIST' = 'No prior recommendation'
        'NO_GOVERNED_TRANSACTION' = 'No move recommended'
        'RECOMMEND_ADD_DROP' = 'Add / drop recommended'
        'NOT_EVALUATED' = 'Not checked'
        'FLEXIBLE_BALANCED' = 'Balanced'
        'READY_CONTEXT_ONLY' = 'Ready'
        'BOUND_TARGET_LIVE_VERIFIED' = 'Verified'
        'LIVE_ACTIONABLE_VERIFIED' = 'Verified'
        'AUDITED_TRANSACTION_COMPLETE' = 'Move completed'
        'AUDITED_TRANSACTION_PENDING' = 'Move pending'
        'NO_TRANSACTION_TO_REVALIDATE' = 'No move to recheck'
        'LATEST_EVIDENCE_LINEAGE_VERIFIED' = 'Up to date'
        'CURRENT_AND_ACTIONABLE' = 'Ready to act'
        'TRANSACTION_ALREADY_COMPLETE' = 'Move completed'
        'TRANSACTION_PENDING_DO_NOT_DUPLICATE' = 'Move pending'
        'CURRENT_REFRESH_RECOMMENDED' = 'Refresh recommended'
        'STALE_DO_NOT_ACT' = 'Do not act'
        'NO_TRANSACTION_TO_ACT_ON' = 'No move recommended'
        'MANUAL_REFRESH_PLAN_READY' = 'Refresh ready'
        'POST_TRANSACTION_ROSTER_CONVERGED' = 'Roster updated'
    }
    if ($labels.ContainsKey($Value)) { return [string]$labels[$Value] }

    $words = $Value.Replace('_', ' ').ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($words)) { return $Value }
    return $words.Substring(0, 1).ToUpperInvariant() + $words.Substring(1)
}

function ConvertTo-ButlerUserFacingHtml {
    param([Parameter(Mandatory = $true)][string]$Html)

    if ([string]::IsNullOrWhiteSpace($Html)) { return $Html }

    # Keep exact raw command/diagnostic material available behind technical
    # disclosures while normal visible copy is normalized.
    $preBlocks = New-Object System.Collections.Generic.List[string]
    $preEvaluator = [System.Text.RegularExpressions.MatchEvaluator]{
        param($match)
        $index = $preBlocks.Count
        $preBlocks.Add($match.Value)
        return "@@butler-pre-$index@@"
    }
    $result = [regex]::Replace($Html, '(?is)<pre\b[^>]*>.*?</pre>', $preEvaluator)

    $copy = [ordered]@{
        'BF-628 integrity-verified history for the exact BF-623-bound league and roster. This page does not rerun recommendations.' = 'Past waiver recommendations for this team. Butler does not rerun decisions on this page.'
        'Loading Butler''s BF-628 integrity-verified immutable waiver audit trail.' = 'Loading your saved waiver decisions.'
        'This view reads existing audit history only. It does not capture, refresh, rerank, or submit anything.' = 'This page only shows saved decisions. It does not refresh data or submit moves.'
        'Immutable governed waiver audits' = 'Waiver decision history'
        'Governed decision history' = 'Decision history'
        'Immutable audit' = 'Decision record'
        'Immutable Butler audit captured' = 'Saved decision'
        'Every governed recommendation remains traceable even after your roster changes.' = 'This recommendation stays saved even if your roster changes.'
        'History state' = 'History status'
        'Audit records' = 'Saved decisions'
        'Target roster' = 'Roster'
        'Provider frame' = 'Season / week'
        'Selection state' = 'Decision status'
        'Audit and evidence lineage' = 'Decision details'
        'Show audit details' = 'Show decision details'
        'Governed trade evaluation' = 'Trade evaluation'
        'Package recommendation:' = 'Butler recommendation:'
        'Perspective is always your exact bound team.' = 'This is evaluated from your team''s perspective.'
        'INCOMPLETE EVIDENCE' = 'Missing information'
        'COMPLETE EVIDENCE' = 'Ready'
        'Strategic veto' = 'Deal-breaker check'
        'Flexible pressure' = 'Roster flexibility'
        'Pressure transition' = 'Roster impact'
        '<strong>Market direction</strong>' = '<strong>Market</strong>'
        '<strong>Posture</strong>' = '<strong>Team strategy</strong>'
        '<strong>Future capital</strong>' = '<strong>Draft capital</strong>'
        '<strong>Position pressure</strong>' = '<strong>Position need</strong>'
        'Material-loss veto evidence' = 'Deal-breakers'
        'No governed material-loss veto reason was returned.' = 'No deal-breaker found.'
        'Technical governed output' = 'Technical details'
        'Butler''s existing governed v5 trade recommendation, brought into a read-only app workspace. No new trade score is created here.' = 'Compare a potential trade using Butler''s current recommendation model. This page does not make roster changes or submit trades.'
        'Select exact persisted assets. Butler validates current ownership again before evaluation.' = 'Select the players and picks on each side. Butler checks current ownership before evaluating the deal.'
        'Butler''s existing governed league overview, presented as an app view without adding a new ranking or strategy model.' = 'A snapshot of league strength, value movement, and what needs attention.'
        'Safe franchise context' = 'League standings'
        'Shown only when Butler''s existing franchise-readiness gate authorizes rankings.' = 'Based on the latest complete league data Butler has available.'
        'Comparable history' = 'Value trends'
        'Governed guidance' = 'What to do next'
        'These are Butler''s existing deterministic league-health actions. Commands are displayed for manual use only and are never executed by this page.' = 'Recommended next steps based on the league data Butler has available.'
        'My team intelligence' = 'My Team'
        'Butler''s existing governed team evidence composed into one read-only app screen. No new team score or strategy model is created here.' = 'A snapshot of your roster, strengths, needs, and future draft capital.'
        'LIVE VERIFIED' = 'Up to date'
        'Governed dimensions' = 'Team outlook'
        'Team posture' = 'Team direction'
        'Lineup-aware pressure' = 'Position needs'
        'These are Butler''s existing positional-pressure tiers. FLEX/SUPERFLEX remain separate governed context; this page does not turn them into start/sit advice.' = 'See where your roster is strong, balanced, or thin by position. FLEX and SUPERFLEX are considered without turning this into start/sit advice.'
        'Future flexibility' = 'Draft capital'
        'Exact BF-623-bound live roster context from BF-610. Players are grouped for readability only, not ranked.' = 'Your current Sleeper roster, grouped by position.'
        'Governed posture unavailable' = 'Team direction unavailable'
        'No governed player' = 'No player'
        'latest governed evidence' = 'latest verified information'
        'exact governed transaction' = 'exact move'
        'This governed move' = 'This move'
        'new governed result' = 'new recommendation'
        'governed evaluation' = 'evaluation'
        'No governed transaction' = 'No move recommended'
        'governed transaction' = 'recommended move'
        'currently governed add/drop action' = 'current add/drop recommendation'
        'Evidence lineage verified' = 'Recommendation data verified'
        'Evidence lineage not current' = 'Recommendation data needs refresh'
        'governed evidence refresh' = 'data refresh'
        'No governed next action' = 'No next action'
        'governed movement window' = 'value-trend window'
        'comparable provider snapshots' = 'comparable value snapshots'
        'provider snapshots' = 'value snapshots'
        'INCONCLUSIVE' = 'No clear recommendation'
        'The final method did not produce one unique evidence-supported add/drop pair. Cross-position ties or incompatible evidence remain unresolved; Butler will not manufacture a tiebreaker.' = 'Several waiver options were too close to separate confidently, so Butler did not recommend a move. When the evidence does not clearly favor one add/drop combination, Butler would rather make no recommendation than guess.'
        'Persisted BF-653 explanation for this immutable BF-627 audit. This dashboard does not rerun recommendation or evidence selection.' = 'This explanation was saved with the decision. Butler is showing the original result, not recalculating it now.'
        'Governed interpretation' = 'What it means'
        'Newcomer review remains explicitly nonnumeric. Butler does not fabricate a production score or rank this player against historical candidates.' = 'Butler does not have enough comparable history to rank this player confidently yet. The player stays under review without a made-up score.'
        'NOT A RANKING. This page inspects one exact authorized candidate. It does not change Butler''s current recommendation.' = 'PLAYER REVIEW. This page explains why Butler is watching this player. It does not change the current waiver recommendation.'
        'BF-616 lane' = 'Review type'
        'Capture future value snapshot' = 'Check again after the next value update'
        'Capture a later provider value snapshot when available; movement analysis needs two source snapshots.' = 'Butler needs another value update before it can show a meaningful trend. Check back after the next snapshot is available.'
        'Comparator traceability' = 'Advanced comparison details'
        'Technical details' = 'Advanced technical record'
        'Technical and audit details' = 'Advanced technical record'
        'Show decision details' = 'Advanced technical record'
        'Decision details' = 'Advanced technical record'
        'BF-603 market:' = 'Value snapshot ID:'
        'BF-602 waiver:' = 'Waiver snapshot ID:'
        'ADD / DROP Sleeper ids:' = 'Player IDs (add / drop):'
        'READ ONLY &middot; EXACT ID ONLY.' = 'READ ONLY.'
        'READ ONLY &middot; NOT A RANKING.' = 'READ ONLY.'
    }
    foreach ($key in $copy.Keys) {
        $result = $result.Replace([string]$key, [string]$copy[$key])
    }

    # BF-799 keeps the saved-decision title and description visually separate
    # regardless of inherited dashboard display styles.
    $result = $result.Replace(
        '<div class="lineage-copy"><strong>Saved decision</strong><span>',
        '<div class="lineage-copy"><strong style="display:block;margin-bottom:6px">Saved decision</strong><span style="display:block">'
    )

    # BF-792 keeps provider lifecycle names out of normal fantasy-football UI.
    # Match only visible season/status/leg text frames; persisted provider values
    # and raw technical <pre> diagnostics remain exact.
    $result = [regex]::Replace($result, '(?i)(>\s*\d{4})\s*/\s*in_season\s*/\s*(\d+)(\s*<)', '$1 / Week $2$3')
    $result = [regex]::Replace($result, '(?i)(>\s*\d{4})\s*/\s*pre_draft\s*/\s*\d+(\s*<)', '$1 / Pre-draft$2')
    $result = [regex]::Replace($result, '(?i)(>\s*\d{4})\s*/\s*drafting\s*/\s*\d+(\s*<)', '$1 / Drafting$2')
    $result = [regex]::Replace($result, '(?i)(>\s*\d{4})\s*/\s*complete\s*/\s*\d+(\s*<)', '$1 / Complete$2')
    $result = [regex]::Replace($result, '(?i)(>\s*\d{4})\s*/\s*post_season\s*/\s*\d+(\s*<)', '$1 / Post-season$2')

    # BF-793 keeps implementation traces out of the normal scan path. Exact
    # ids and commands remain available, but only behind explicitly advanced
    # disclosures that are collapsed by default.
    $result = $result.Replace(
        '<details open><summary>Advanced comparison details</summary>',
        '<details><summary>Advanced comparison details</summary><p class="subtle">Troubleshooting data only. It does not change Butler''s recommendation.</p>'
    )

    # BF-794 removes technical record disclosures from normal user-facing HTML.
    # Exact audit/snapshot identifiers remain in source records and backend
    # diagnostics; they are not part of the fantasy-manager product surface.
    $result = [regex]::Replace(
        $result,
        '(?is)<details\b[^>]*>\s*<summary>Advanced technical record</summary>.*?</details>',
        ''
    )

    $result = [regex]::Replace(
        $result,
        '(<input class="command" readonly value="[^"]*">)',
        # BF-799 keeps maintenance commands out of the normal fantasy-manager UI.
        # Source commands remain available to operators and backend diagnostics.
        ''
    )
    $result = [regex]::Replace(
        $result,
        '(?i)<strong>Market attention:</strong>\s*add\s+(-?\d+)\s*/\s*drop\s+(-?\d+)\s*/\s*net\s+(-?\d+)',
        '<strong>Recent Sleeper activity:</strong> $1 adds &middot; $2 drops &middot; net $3'
    )
    $result = [regex]::Replace($result, '(?i)\s*&middot;\s*Sleeper\s+\d+', '')

    # Evidence-gate statuses use READY/BLOCKED internally. Their UI meaning is
    # simply whether enough information is available for that part of the call.
    $result = $result.Replace('<span>READY</span>', '<span>Ready</span>')
    $result = $result.Replace('<span>BLOCKED</span>', '<span>Missing</span>')
    $result = $result.Replace('<span class="danger">BLOCKED</span>', '<span class="danger">Deal-breaker found</span>')
    $result = $result.Replace('<span class="done">NOT_EVALUATED</span>', '<span class="done">Not checked</span>')

    # The normal app never needs engineering ticket numbers or Butler-internal
    # UUIDs in its headers/cards. Functional ids in form values/links are left
    # untouched because these patterns target visible HTML containers only.
    if ($result -match '<title>Butler - Decision History</title>') {
        $result = [regex]::Replace($result, '(?is)<div class="target">.*?</div>', '<div class="target">Decision history</div>')
    }
    if ($result -match '<title>Butler - Decision Detail</title>') {
        $result = [regex]::Replace(
            $result,
            '(?is)<div class="target">.*?</div>',
            '<div class="target">Decision history</div>'
        )
        $result = [regex]::Replace(
            $result,
            '(?is)<div class="detail-item"><strong>Explanation id</strong><span>.*?</span></div>',
            ''
        )
    }

    $result = [regex]::Replace($result, '(?i)\s*&middot;\s*roster\s+\d+(?=</div>)', '')
    $result = [regex]::Replace($result, '(?i)(<div class="target">[^<]*?)\s*&middot;\s*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(</div>)', '$1$2')
    $result = [regex]::Replace($result, '(?i)<div class="meta">Team ID\s+[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}</div>', '')

    # Replace known and future enum-shaped state names with readable labels.
    $enumEvaluator = [System.Text.RegularExpressions.MatchEvaluator]{
        param($match)
        return ConvertTo-ButlerDisplayToken -Value $match.Value
    }
    $result = [regex]::Replace($result, '\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b', $enumEvaluator)

    # Remaining engineering vocabulary is presentation-only cleanup. Raw `<pre>`
    # output was protected above and therefore still carries the exact source.
    $result = [regex]::Replace($result, '(?i)\bBF-\d+(?:-bound)?\b', '')
    $result = [regex]::Replace($result, '(?i)\bevidence lineage\b', 'recommendation data')
    $result = [regex]::Replace($result, '(?i)\blineage\b', 'source data')
    $result = [regex]::Replace($result, '(?i)\bgoverned\s+', '')
    $result = [regex]::Replace($result, '(?i)\bpersisted\b', 'saved')
    $result = [regex]::Replace($result, '(?i)\bbound\b', 'linked')
    $result = [regex]::Replace($result, '(?i)\bdeterministic\s+', '')
    $result = [regex]::Replace($result, '(?i)\baudited\b', 'recorded')
    $result = [regex]::Replace($result, '(?i)\baudit history\b', 'decision history')
    $result = [regex]::Replace($result, '(?i)\baudit records?\b', 'saved decisions')

    # Standardize the promise at the bottom of every normal read-only screen.
    $result = [regex]::Replace(
        $result,
        '(?is)<section class="panel boundary"><span class="lock">READ ONLY\.</span>.*?</section>',
        '<section class="panel boundary"><span class="lock">READ ONLY.</span> Butler will never make roster changes or submit a Sleeper transaction from this screen.</section>'
    )

    for ($index = 0; $index -lt $preBlocks.Count; $index++) {
        $result = $result.Replace("@@butler-pre-$index@@", $preBlocks[$index])
    }
    return $result
}
'@

    $sendMarkerIndex = $sendMarkerMatches[0].Index
    $implementation = $implementation.Substring(0, $sendMarkerIndex) +
        $presentationFunctions + "`r`n`r`n" +
        $implementation.Substring($sendMarkerIndex)

    $bodyEncodingLine = '    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($Body)'
    $bodyEncodingMatches = [regex]::Matches($implementation, [regex]::Escape($bodyEncodingLine))
    if ($bodyEncodingMatches.Count -ne 1) {
        try { $Client.Close() } catch {}
        throw "BF-791 BLOCKED: public HTML encoding boundary count was $($bodyEncodingMatches.Count), expected exactly 1."
    }
    $bodyEncodingReplacement = @'
    if ($ContentType -match '^text/html') {
        $bf856PresentationStarted = if ($bf856RouteTimingEnabled -and $null -ne $DiagnosticTimings) {
            [System.Diagnostics.Stopwatch]::GetTimestamp()
        } else {
            [long]0
        }
        # BF-890 lets the owned BF-885 journey inspect the original fail-closed
        # recovery detail. Successful pages and every normal Butler launch keep
        # the existing BF-791/BF-794 user-facing presentation cleanup.
        $bf890AcceptanceDiagnostic =
            [string]$env:BUTLER_BF890_ACCEPTANCE_DIAGNOSTICS -ceq '1' -and
            $StatusCode -ge 400
        if (-not $bf890AcceptanceDiagnostic) {
            $Body = ConvertTo-ButlerUserFacingHtml -Html $Body
        }
        if ($bf856RouteTimingEnabled -and $null -ne $DiagnosticTimings) {
            $DiagnosticTimings.presentation_ms = Get-Bf856ElapsedMs -StartedTicks $bf856PresentationStarted
        }
    }
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
'@
    $implementation = $implementation.Replace($bodyEncodingLine, $bodyEncodingReplacement.TrimEnd())

    $worker = [scriptblock]::Create($implementation)
    Set-Variable -Name $cacheName -Scope Global -Value $worker
}
elseif ($worker -isnot [scriptblock]) {
    try { $Client.Close() } catch {}
    throw 'BF-765 BLOCKED: cached public request worker has an unexpected type.'
}

& $worker `
    -Client $Client `
    -LeagueId $LeagueId `
    -InnerPort $InnerPort `
    -TradeHost $TradeHost `
    -TradeLab $TradeLab `
    -History $History `
    -Detail $Detail `
    -DecisionRefresh $DecisionRefresh `
    -DecisionRefreshRunner $DecisionRefreshRunner `
    -RepoRoot $RepoRoot `
    -RefreshState $RefreshState
