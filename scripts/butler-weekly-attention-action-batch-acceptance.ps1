Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

foreach ($name in @(
    'butler-app-bf980-weekly-attention-waiver-actions-transform.ps1',
    'butler-app-bf981-weekly-attention-player-drilldown-transform.ps1'
)) {
    $path = Join-Path $PSScriptRoot $name
    $tokens = $null
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -ne 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "WEEKLY ATTENTION ACTION BATCH BLOCKED: transform parse failed: $name :: $summary"
    }
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-weekly-attention-action-batch-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $corePath = Join-Path $root 'butler-app-shell-core-single.ps1'
    $dashboardPath = Join-Path $root 'butler-dashboard.ps1'

    $coreTokens = $null
    $coreErrors = $null
    $coreAst = [System.Management.Automation.Language.Parser]::ParseFile($corePath, [ref]$coreTokens, [ref]$coreErrors)
    if (@($coreErrors).Count -ne 0) {
        $summary = (@($coreErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "WEEKLY ATTENTION ACTION BATCH BLOCKED: staged core parse failed: $summary"
    }

    $dashboardTokens = $null
    $dashboardErrors = $null
    $dashboardAst = [System.Management.Automation.Language.Parser]::ParseFile($dashboardPath, [ref]$dashboardTokens, [ref]$dashboardErrors)
    if (@($dashboardErrors).Count -ne 0) {
        $summary = (@($dashboardErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "WEEKLY ATTENTION ACTION BATCH BLOCKED: staged Dashboard parse failed: $summary"
    }

    function Get-OneFunction {
        param(
            [Parameter(Mandatory = $true)]$Ast,
            [Parameter(Mandatory = $true)][string]$Name
        )

        $matches = @($Ast.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $Name
        }, $true))
        if ($matches.Count -ne 1) {
            throw "WEEKLY ATTENTION ACTION BATCH BLOCKED: expected one $Name function, found $($matches.Count)."
        }
        return $matches[0]
    }

    function ConvertTo-HtmlText {
        param([AllowNull()]$Value)
        return [System.Net.WebUtility]::HtmlEncode([string]$Value)
    }

    $liveActionText = (Get-OneFunction -Ast $coreAst -Name 'Get-Bf980StarterWaiverActionsHtml').Extent.Text
    $cachedActionText = (Get-OneFunction -Ast $dashboardAst -Name 'Get-Bf980SnapshotStarterWaiverActionsHtml').Extent.Text
    $liveAttentionText = (Get-OneFunction -Ast $coreAst -Name 'Get-Bf979WeeklyAttentionHtml').Extent.Text
    $cachedAttentionText = (Get-OneFunction -Ast $dashboardAst -Name 'Get-Bf979SnapshotWeeklyAttentionHtml').Extent.Text
    $dashboardReturnText = (Get-OneFunction -Ast $coreAst -Name 'Add-Bf981PlayerDetailDashboardReturn').Extent.Text

    Invoke-Expression $liveActionText
    Invoke-Expression $cachedActionText
    Invoke-Expression $liveAttentionText
    Invoke-Expression $cachedAttentionText
    Invoke-Expression $dashboardReturnText

    $holds = @(
        [pscustomobject]@{ Name = 'WR Starter'; Id = '201'; RosterSlot = 'STARTER'; LineupSlot = 'WR'; Status = 'Active'; InjuryStatus = 'Questionable'; Reason = 'Review.' },
        [pscustomobject]@{ Name = 'Second WR Starter'; Id = '202'; RosterSlot = 'STARTER'; LineupSlot = 'wr'; Status = 'Active'; InjuryStatus = 'Questionable'; Reason = 'Review.' },
        [pscustomobject]@{ Name = 'RB Starter'; Id = '203'; RosterSlot = 'STARTER'; LineupSlot = 'rb'; Status = 'Active'; InjuryStatus = 'Questionable'; Reason = 'Review.' },
        [pscustomobject]@{ Name = 'TE Bench'; Id = '204'; RosterSlot = 'BENCH'; LineupSlot = 'TE'; Status = 'Active'; InjuryStatus = 'Questionable'; Reason = 'Bench hold.' },
        [pscustomobject]@{ Name = 'Flex Starter'; Id = '205'; RosterSlot = 'STARTER'; LineupSlot = 'FLEX'; Status = 'Active'; InjuryStatus = 'Questionable'; Reason = 'Flex hold.' }
    )
    $availability = @(
        [pscustomobject]@{ Name = 'Unavailable Player'; Id = '206'; Status = 'Inactive'; InjuryStatus = 'Out'; Reason = 'Unavailable.' }
    )

    $liveActions = Get-Bf980StarterWaiverActionsHtml -Holds $holds
    $cachedActions = Get-Bf980SnapshotStarterWaiverActionsHtml -Holds $holds
    foreach ($pair in @(
        @('live', $liveActions),
        @('cached', $cachedActions)
    )) {
        $label = [string]$pair[0]
        $html = [string]$pair[1]

        foreach ($required in @(
            'href="/waivers?position=RB">Check RB waivers</a>',
            'href="/waivers?position=WR">Check WR waivers</a>'
        )) {
            if ($html.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
                throw "WEEKLY ATTENTION ACTION BATCH BLOCKED: $label focused-waiver action is missing: $required"
            }
        }

        $wrCount = [regex]::Matches($html, [regex]::Escape('href="/waivers?position=WR"')).Count
        if ($wrCount -ne 1) {
            throw "WEEKLY ATTENTION ACTION BATCH BLOCKED: $label duplicate WR actions were not collapsed; found $wrCount."
        }

        foreach ($forbidden in @('position=TE', 'position=FLEX', 'position=QB')) {
            if ($html.IndexOf($forbidden, [System.StringComparison]::Ordinal) -ge 0) {
                throw "WEEKLY ATTENTION ACTION BATCH BLOCKED: $label surfaced an unsupported or bench-only waiver action: $forbidden"
            }
        }
    }

    $autoFill = [pscustomobject]@{
        Requested = $true
        Ready = $true
        AvailabilityExclusions = @($availability)
        ProjectionHolds = @($holds)
    }
    $liveHtml = Get-Bf979WeeklyAttentionHtml -AutoFill $autoFill
    foreach ($required in @(
        'href="/player?id=206">Unavailable Player</a>',
        'href="/player?id=201">WR Starter</a>',
        'Check RB waivers',
        'Check WR waivers',
        'Position-focused waiver links filter the board only; they do not choose a replacement.'
    )) {
        if ($liveHtml.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "WEEKLY ATTENTION ACTION BATCH BLOCKED: live Weekly Attention marker is missing: $required"
        }
    }

    $snapshot = [pscustomobject]@{
        Ready = $true
        AvailabilityExclusions = @($availability)
        ProjectionHolds = @($holds)
    }
    $cachedHtml = Get-Bf979SnapshotWeeklyAttentionHtml -Snapshot $snapshot -LineupSignalStatus 'AUTOFILL READY'
    foreach ($required in @(
        'href="/player?id=206&amp;from=dashboard">Unavailable Player</a>',
        'href="/player?id=201&amp;from=dashboard">WR Starter</a>',
        'Check RB waivers',
        'Check WR waivers',
        'Position-focused waiver links filter the board only; they do not choose a replacement.'
    )) {
        if ($cachedHtml.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "WEEKLY ATTENTION ACTION BATCH BLOCKED: cached Dashboard attention marker is missing: $required"
        }
    }

    $availabilityOnly = [pscustomobject]@{
        Requested = $true
        Ready = $true
        AvailabilityExclusions = @($availability)
        ProjectionHolds = @()
    }
    $availabilityOnlyHtml = Get-Bf979WeeklyAttentionHtml -AutoFill $availabilityOnly
    if ($availabilityOnlyHtml.IndexOf('/waivers?position=', [System.StringComparison]::Ordinal) -ge 0) {
        throw 'WEEKLY ATTENTION ACTION BATCH BLOCKED: unpositioned availability evidence guessed a focused Waiver Board filter.'
    }
    if ($availabilityOnlyHtml.IndexOf('href="/player?id=206">Unavailable Player</a>', [System.StringComparison]::Ordinal) -lt 0) {
        throw 'WEEKLY ATTENTION ACTION BATCH BLOCKED: availability-only evidence lost exact-player drill-down.'
    }

    $benchOnly = @(
        [pscustomobject]@{ Name = 'Bench TE'; Id = '207'; RosterSlot = 'BENCH'; LineupSlot = 'TE'; Status = 'Active'; InjuryStatus = 'Questionable'; Reason = 'Bench only.' }
    )
    if (-not [string]::IsNullOrWhiteSpace((Get-Bf980StarterWaiverActionsHtml -Holds $benchOnly))) {
        throw 'WEEKLY ATTENTION ACTION BATCH BLOCKED: bench-only hold generated a focused waiver action.'
    }
    if (-not [string]::IsNullOrWhiteSpace((Get-Bf980SnapshotStarterWaiverActionsHtml -Holds $benchOnly))) {
        throw 'WEEKLY ATTENTION ACTION BATCH BLOCKED: cached bench-only hold generated a focused waiver action.'
    }

    $detailHtml = '<div class="button-row"><a class="btn btn-secondary" href="/team">Back to My Team</a><a class="btn btn-secondary" href="/players">Player Search</a></div>'
    $dashboardDetailHtml = Add-Bf981PlayerDetailDashboardReturn -Html $detailHtml -FromDashboard $true
    foreach ($required in @(
        'href="/">Back to Dashboard</a>',
        'href="/team">My Team</a>',
        'href="/players">Player Search</a>'
    )) {
        if ($dashboardDetailHtml.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "WEEKLY ATTENTION ACTION BATCH BLOCKED: Dashboard Player Detail return marker is missing: $required"
        }
    }

    $unchangedDetailHtml = Add-Bf981PlayerDetailDashboardReturn -Html $detailHtml -FromDashboard $false
    if ($unchangedDetailHtml -cne $detailHtml) {
        throw 'WEEKLY ATTENTION ACTION BATCH BLOCKED: non-Dashboard Player Detail navigation was changed.'
    }

    $core = [IO.File]::ReadAllText($corePath)
    foreach ($required in @(
        '(?:?|&)from=dashboard(?:&|$)',
        'Add-Bf981PlayerDetailDashboardReturn -Html $html -FromDashboard $fromDashboard',
        'function Get-Bf980StarterWaiverActionsHtml',
        'function Add-Bf981PlayerDetailDashboardReturn'
    )) {
        if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "WEEKLY ATTENTION ACTION BATCH BLOCKED: final core route/helper marker is missing: $required"
        }
    }

    $surface = $liveActionText + [Environment]::NewLine + $cachedActionText + [Environment]::NewLine + $dashboardReturnText
    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'WEEKLY ATTENTION ACTION BATCH BLOCKED: attention actions introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
    }

    Write-Host 'BUTLER WEEKLY ATTENTION ACTION BATCH ACCEPTANCE: PASS'
    Write-Host 'Checks passed: BF-980 focused starter waiver filters, BF-981 exact-player drill-down with Dashboard return'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
