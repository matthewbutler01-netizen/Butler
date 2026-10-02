Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf982-weekly-attention-replacement-context-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-982 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf982-replacement-context-' + [guid]::NewGuid().ToString('N'))
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
        throw "BF-982 BLOCKED: staged core has parse errors: $summary"
    }

    $dashboardTokens = $null
    $dashboardErrors = $null
    $dashboardAst = [System.Management.Automation.Language.Parser]::ParseFile($dashboardPath, [ref]$dashboardTokens, [ref]$dashboardErrors)
    if (@($dashboardErrors).Count -ne 0) {
        $summary = (@($dashboardErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-982 BLOCKED: staged Dashboard has parse errors: $summary"
    }

    function Get-OneFunction {
        param(
            [Parameter(Mandatory = $true)]$Ast,
            [Parameter(Mandatory = $true)][string]$Name
        )

        $matches = @($Ast.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -eq $Name
        }, $true))
        if ($matches.Count -ne 1) {
            throw "BF-982 BLOCKED: expected one $Name function, found $($matches.Count)."
        }
        return $matches[0]
    }

    function ConvertTo-HtmlText {
        param([AllowNull()]$Value)
        return [System.Net.WebUtility]::HtmlEncode([string]$Value)
    }

    $liveWaiverActionsText = (Get-OneFunction -Ast $coreAst -Name 'Get-Bf980StarterWaiverActionsHtml').Extent.Text
    $cachedWaiverActionsText = (Get-OneFunction -Ast $dashboardAst -Name 'Get-Bf980SnapshotStarterWaiverActionsHtml').Extent.Text
    $liveAttentionText = (Get-OneFunction -Ast $coreAst -Name 'Get-Bf979WeeklyAttentionHtml').Extent.Text
    $cachedAttentionText = (Get-OneFunction -Ast $dashboardAst -Name 'Get-Bf979SnapshotWeeklyAttentionHtml').Extent.Text
    $rosterFocusText = (Get-OneFunction -Ast $dashboardAst -Name 'Get-WaiverReplacementRosterFocusFromRequestTarget').Extent.Text
    $waiverText = (Get-OneFunction -Ast $dashboardAst -Name 'ConvertTo-WaiverHtml').Extent.Text
    $candidateText = (Get-OneFunction -Ast $dashboardAst -Name 'ConvertTo-WaiverCandidateDetailHtml').Extent.Text
    $rosterCompareText = (Get-OneFunction -Ast $dashboardAst -Name 'ConvertTo-WaiverRosterCompareHtml').Extent.Text

    Invoke-Expression $liveWaiverActionsText
    Invoke-Expression $cachedWaiverActionsText
    Invoke-Expression $liveAttentionText
    Invoke-Expression $cachedAttentionText
    Invoke-Expression $rosterFocusText

    $hold = [pscustomobject]@{
        Name = 'Held WR Starter'
        Id = '201'
        RosterSlot = 'STARTER'
        LineupSlot = 'wr'
        Status = 'Active'
        InjuryStatus = 'Questionable'
        Reason = 'Review.'
    }

    $autoFill = [pscustomobject]@{
        Requested = $true
        Ready = $true
        AvailabilityExclusions = @()
        ProjectionHolds = @($hold)
    }

    $liveHtml = Get-Bf979WeeklyAttentionHtml -AutoFill $autoFill
    foreach ($required in @(
        'Held WR Starter',
        'href="/waivers?position=WR&amp;roster=201">Find WR replacement</a>',
        'href="/player?id=201">Held WR Starter</a>'
    )) {
        if ($liveHtml.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-982 BLOCKED: live replacement-context marker is missing: $required"
        }
    }

    $snapshot = [pscustomobject]@{
        Ready = $true
        AvailabilityExclusions = @()
        ProjectionHolds = @($hold)
    }

    $cachedHtml = Get-Bf979SnapshotWeeklyAttentionHtml -Snapshot $snapshot -LineupSignalStatus 'AUTOFILL READY'
    foreach ($required in @(
        'Held WR Starter',
        'href="/waivers?position=WR&amp;roster=201">Find WR replacement</a>',
        'href="/player?id=201&amp;from=dashboard">Held WR Starter</a>'
    )) {
        if ($cachedHtml.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-982 BLOCKED: cached replacement-context marker is missing: $required"
        }
    }

    foreach ($case in @(
        [pscustomobject]@{ Target = '/waivers?position=WR&roster=201'; Expected = '201' },
        [pscustomobject]@{ Target = '/waivers?roster=201&position=WR'; Expected = '201' },
        [pscustomobject]@{ Target = '/waivers?position=WR'; Expected = '' },
        [pscustomobject]@{ Target = '/waivers?position=WR&roster=abc'; Expected = '' },
        [pscustomobject]@{ Target = '/waivers?position=WR&roster=201&roster=202'; Expected = '' }
    )) {
        $actual = Get-WaiverReplacementRosterFocusFromRequestTarget -RequestTarget $case.Target
        if ([string]$actual -cne [string]$case.Expected) {
            throw "BF-982 BLOCKED: roster-focus parser mismatch for $($case.Target); expected '$($case.Expected)' got '$actual'."
        }
    }

    foreach ($required in @(
        '[string]$RosterFocus = ""',
        'Resolve-WaiverRosterPlayerById -RosterContext $RosterContext -SleeperId $normalizedRosterFocus',
        '[string]$candidateRosterPlayer.RosterSlot -ceq ''STARTER''',
        '([string]$candidateRosterPlayer.Position).Trim().ToUpperInvariant() -ceq $normalizedPositionFocus',
        'Compare to held starter',
        'Replacement context',
        'No replacement has been selected.',
        '$waiverReplacementBoardSuffix',
        '$cards = $cards.Replace($waiverPositionBoardSuffix + ''">View governed details</a>'', $waiverReplacementBoardSuffix + ''">View governed details</a>'')'
    )) {
        if ($waiverText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-982 BLOCKED: Waiver Board replacement-context contract is missing: $required"
        }
    }

    foreach ($required in @(
        '[string]$RosterFocus = ""',
        'Compare to replacement context',
        '$waiverReplacementBoardSuffix',
        'Back to Waiver Board'
    )) {
        if ($candidateText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-982 BLOCKED: Candidate Detail replacement-context contract is missing: $required"
        }
    }

    foreach ($required in @(
        '$waiverReplacementBoardSuffix = $waiverPositionBoardSuffix',
        'href="/waivers$waiverReplacementBoardSuffix">'
    )) {
        if ($rosterCompareText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-982 BLOCKED: Roster Compare replacement-context return is missing: $required"
        }
    }

    $dashboardSource = [IO.File]::ReadAllText($dashboardPath)
    foreach ($required in @(
        '-RosterFocus (Get-WaiverReplacementRosterFocusFromRequestTarget -RequestTarget $parts[1])',
        'function Get-WaiverReplacementRosterFocusFromRequestTarget',
        'function ConvertTo-WaiverHtml',
        'function ConvertTo-WaiverCandidateDetailHtml',
        'function ConvertTo-WaiverRosterCompareHtml'
    )) {
        if ($dashboardSource.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-982 BLOCKED: final staged route/helper marker is missing: $required"
        }
    }

    $routeCount = [regex]::Matches(
        $dashboardSource,
        [regex]::Escape('-RosterFocus (Get-WaiverReplacementRosterFocusFromRequestTarget -RequestTarget $parts[1])')
    ).Count
    if ($routeCount -ne 2) {
        throw "BF-982 BLOCKED: expected replacement roster context on Board and Candidate Detail routes only; found $routeCount."
    }

    $coreSource = [IO.File]::ReadAllText($corePath)
    $proxyMarker = '$dashboardRequestTarget = if ($path -eq "/waivers" -or $path -eq "/waivers/compare" -or $path -eq "/waivers/roster-compare" -or $candidate) { $parts[1] } else { $path }'
    if ($coreSource.IndexOf($proxyMarker, [System.StringComparison]::Ordinal) -lt 0) {
        throw 'BF-982 BLOCKED: app shell no longer preserves full waiver/candidate query context.'
    }

    $surface = $rosterFocusText
    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-982 BLOCKED: replacement roster parser introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
    }

    Write-Host 'BF-982 WEEKLY ATTENTION REPLACEMENT CONTEXT ACCEPTANCE: PASS'
    Write-Host 'Coverage: exact held starter -> focused board -> direct candidate/roster compare -> preserved Board return; invalid context degrades safely'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
