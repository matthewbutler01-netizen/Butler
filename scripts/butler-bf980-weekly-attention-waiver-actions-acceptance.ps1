Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf980-weekly-attention-waiver-actions-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-980 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf980-waiver-actions-' + [guid]::NewGuid().ToString('N'))
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
        throw "BF-980 BLOCKED: staged core has parse errors: $summary"
    }

    $dashboardTokens = $null
    $dashboardErrors = $null
    $dashboardAst = [System.Management.Automation.Language.Parser]::ParseFile($dashboardPath, [ref]$dashboardTokens, [ref]$dashboardErrors)
    if (@($dashboardErrors).Count -ne 0) {
        $summary = (@($dashboardErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-980 BLOCKED: staged Dashboard has parse errors: $summary"
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
            throw "BF-980 BLOCKED: expected one $Name function, found $($matches.Count)."
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

    foreach ($required in @(
        '@(''QB'', ''RB'', ''WR'', ''TE'') -ccontains $position',
        '[string]$_.RosterSlot -ceq ''STARTER''',
        'Sort-Object -Unique',
        '/waivers?position=$encodedPosition',
        'Check $(ConvertTo-HtmlText $position) waivers'
    )) {
        if ($liveActionText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-980 BLOCKED: live focused-waiver helper marker is missing: $required"
        }
        if ($cachedActionText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-980 BLOCKED: cached focused-waiver helper marker is missing: $required"
        }
    }

    foreach ($required in @(
        'Get-Bf980StarterWaiverActionsHtml -Holds $holds',
        '$waiverActionHtml</div>',
        'Position-focused waiver links filter the board only; they do not choose a replacement.'
    )) {
        if ($liveAttentionText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-980 BLOCKED: live Weekly Attention action marker is missing: $required"
        }
    }

    foreach ($required in @(
        'Get-Bf980SnapshotStarterWaiverActionsHtml -Holds $holds',
        '$waiverActionHtml</div>',
        'Position-focused waiver links filter the board only; they do not choose a replacement.'
    )) {
        if ($cachedAttentionText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-980 BLOCKED: cached Weekly Attention action marker is missing: $required"
        }
    }

    Invoke-Expression $liveActionText
    Invoke-Expression $cachedActionText
    Invoke-Expression $liveAttentionText
    Invoke-Expression $cachedAttentionText

    $holds = @(
        [pscustomobject]@{ Name = 'WR Starter'; Id = '201'; RosterSlot = 'STARTER'; LineupSlot = 'WR'; Status = 'Active'; InjuryStatus = 'Questionable'; Reason = 'Review.' },
        [pscustomobject]@{ Name = 'Second WR Starter'; Id = '202'; RosterSlot = 'STARTER'; LineupSlot = 'wr'; Status = 'Active'; InjuryStatus = 'Questionable'; Reason = 'Review.' },
        [pscustomobject]@{ Name = 'RB Starter'; Id = '203'; RosterSlot = 'STARTER'; LineupSlot = 'rb'; Status = 'Active'; InjuryStatus = 'Questionable'; Reason = 'Review.' },
        [pscustomobject]@{ Name = 'TE Bench'; Id = '204'; RosterSlot = 'BENCH'; LineupSlot = 'TE'; Status = 'Active'; InjuryStatus = 'Questionable'; Reason = 'Bench hold.' },
        [pscustomobject]@{ Name = 'Flex Starter'; Id = '205'; RosterSlot = 'STARTER'; LineupSlot = 'FLEX'; Status = 'Active'; InjuryStatus = 'Questionable'; Reason = 'Flex hold.' }
    )

    $liveActions = Get-Bf980StarterWaiverActionsHtml -Holds $holds
    $cachedActions = Get-Bf980SnapshotStarterWaiverActionsHtml -Holds $holds
    foreach ($pair in @(
        @('live', $liveActions),
        @('cached', $cachedActions)
    )) {
        $label = [string]$pair[0]
        $actionHtml = [string]$pair[1]
        foreach ($required in @(
            'href="/waivers?position=RB">Check RB waivers</a>',
            'href="/waivers?position=WR">Check WR waivers</a>'
        )) {
            if ($actionHtml.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
                throw "BF-980 BLOCKED: $label focused action is missing: $required"
            }
        }

        $wrCount = [regex]::Matches($actionHtml, [regex]::Escape('href="/waivers?position=WR"')).Count
        if ($wrCount -ne 1) {
            throw "BF-980 BLOCKED: $label helper must collapse duplicate WR starter holds to one link; found $wrCount."
        }
        foreach ($forbidden in @('position=TE', 'position=FLEX', 'position=QB')) {
            if ($actionHtml.IndexOf($forbidden, [System.StringComparison]::Ordinal) -ge 0) {
                throw "BF-980 BLOCKED: $label helper surfaced a non-eligible focused waiver action: $forbidden"
            }
        }
    }

    $autoFill = [pscustomobject]@{
        Requested = $true
        Ready = $true
        AvailabilityExclusions = @()
        ProjectionHolds = @($holds)
    }
    $liveHtml = Get-Bf979WeeklyAttentionHtml -AutoFill $autoFill
    foreach ($required in @(
        'Weekly attention',
        'Review Lineup',
        'Check RB waivers',
        'Check WR waivers',
        'Position-focused waiver links filter the board only; they do not choose a replacement.'
    )) {
        if ($liveHtml.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-980 BLOCKED: live Weekly Attention focused action render is missing: $required"
        }
    }

    $snapshot = [pscustomobject]@{
        Ready = $true
        AvailabilityExclusions = @()
        ProjectionHolds = @($holds)
    }
    $cachedHtml = Get-Bf979SnapshotWeeklyAttentionHtml -Snapshot $snapshot -LineupSignalStatus 'AUTOFILL READY'
    foreach ($required in @(
        'Weekly attention',
        'Review Lineup',
        'Check RB waivers',
        'Check WR waivers',
        'Position-focused waiver links filter the board only; they do not choose a replacement.'
    )) {
        if ($cachedHtml.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-980 BLOCKED: cached Weekly Attention focused action render is missing: $required"
        }
    }

    $availabilityOnly = [pscustomobject]@{
        Requested = $true
        Ready = $true
        AvailabilityExclusions = @(
            [pscustomobject]@{ Name = 'Unavailable Player'; Id = '206'; Status = 'Inactive'; InjuryStatus = 'Out'; Reason = 'Unavailable.' }
        )
        ProjectionHolds = @()
    }
    $availabilityOnlyHtml = Get-Bf979WeeklyAttentionHtml -AutoFill $availabilityOnly
    if ($availabilityOnlyHtml.IndexOf('/waivers?position=', [System.StringComparison]::Ordinal) -ge 0) {
        throw 'BF-980 BLOCKED: availability-only attention guessed a position-focused waiver action.'
    }

    $benchOnly = @(
        [pscustomobject]@{ Name = 'Bench TE'; Id = '207'; RosterSlot = 'BENCH'; LineupSlot = 'TE'; Status = 'Active'; InjuryStatus = 'Questionable'; Reason = 'Bench only.' }
    )
    if (-not [string]::IsNullOrWhiteSpace((Get-Bf980StarterWaiverActionsHtml -Holds $benchOnly))) {
        throw 'BF-980 BLOCKED: bench-only hold generated a focused waiver action.'
    }
    if (-not [string]::IsNullOrWhiteSpace((Get-Bf980SnapshotStarterWaiverActionsHtml -Holds $benchOnly))) {
        throw 'BF-980 BLOCKED: cached bench-only hold generated a focused waiver action.'
    }

    $surface = $liveActionText + [Environment]::NewLine + $cachedActionText
    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-980 BLOCKED: focused waiver helpers introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
    }

    Write-Host 'BF-980 WEEKLY ATTENTION WAIVER ACTIONS ACCEPTANCE: PASS'
    Write-Host 'Coverage: exact starter QB/RB/WR/TE holds -> focused Waiver Board filters; duplicates collapse; bench/FLEX/unpositioned evidence does not guess'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
