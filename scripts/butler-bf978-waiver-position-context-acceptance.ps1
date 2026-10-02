Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-dashboard-bf978-waiver-position-context-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-978 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf978-waiver-position-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $dashboardPath = Join-Path $root 'butler-dashboard.ps1'
    $corePath = Join-Path $root 'butler-app-shell-core-single.ps1'

    $dashboardTokens = $null
    $dashboardErrors = $null
    $dashboardAst = [System.Management.Automation.Language.Parser]::ParseFile($dashboardPath, [ref]$dashboardTokens, [ref]$dashboardErrors)
    if (@($dashboardErrors).Count -ne 0) {
        $summary = (@($dashboardErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-978 BLOCKED: staged Dashboard has parse errors: $summary"
    }

    $coreTokens = $null
    $coreErrors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($corePath, [ref]$coreTokens, [ref]$coreErrors)
    if (@($coreErrors).Count -ne 0) {
        $summary = (@($coreErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-978 BLOCKED: staged app shell has parse errors: $summary"
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
            throw "BF-978 BLOCKED: expected one $Name function, found $($matches.Count)."
        }
        return $matches[0]
    }

    $board = (Get-OneFunction -Ast $dashboardAst -Name 'ConvertTo-WaiverHtml').Extent.Text
    foreach ($required in @(
        '$waiverPositionBoardSuffix',
        '$waiverPositionQuerySuffix',
        'href="/waivers/candidate/$(ConvertTo-HtmlText $candidate.SleeperId)$waiverPositionBoardSuffix"',
        'href="/waivers/compare?left=$(ConvertTo-HtmlText $candidate.SleeperId)$waiverPositionQuerySuffix"',
        'href="/waivers/roster-compare?candidate=$(ConvertTo-HtmlText $candidate.SleeperId)$waiverPositionQuerySuffix"',
        '$waiverQuickActions.Replace(''">Open governed ADD</a>'', $waiverPositionBoardSuffix + ''">Open governed ADD</a>'')',
        '$waiverQuickActions.Replace(''">Compare ADD to roster</a>'', $waiverPositionQuerySuffix + ''">Compare ADD to roster</a>'')'
    )) {
        if ($board.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-978 BLOCKED: Waiver Board position-continuity marker is missing: $required"
        }
    }

    $candidate = (Get-OneFunction -Ast $dashboardAst -Name 'ConvertTo-WaiverCandidateDetailHtml').Extent.Text
    foreach ($required in @(
        '[string]$PositionFocus = ""',
        '$candidateWorkflowActions.Replace(''">Compare candidate</a>'', $waiverPositionQuerySuffix + ''">Compare candidate</a>'')',
        '$candidateWorkflowActions.Replace(''">Compare to roster</a>'', $waiverPositionQuerySuffix + ''">Compare to roster</a>'')',
        'href="/waivers'' + $waiverPositionBoardSuffix + ''">Back to Waiver Board</a>'
    )) {
        if ($candidate.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-978 BLOCKED: Candidate Detail position-continuity marker is missing: $required"
        }
    }

    $compare = (Get-OneFunction -Ast $dashboardAst -Name 'ConvertTo-WaiverCompareHtml').Extent.Text
    foreach ($required in @(
        '[string]$PositionFocus = ""',
        '$swapHref = "/waivers/compare?left=$rightHref&right=$leftHref$waiverPositionQuerySuffix"',
        'href="/waivers/roster-compare?candidate=$leftHref$waiverPositionQuerySuffix"',
        'href="/waivers/candidate/$leftHref$waiverPositionBoardSuffix"',
        'href="/waivers$waiverPositionBoardSuffix">'
    )) {
        if ($compare.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-978 BLOCKED: Candidate Compare position-continuity marker is missing: $required"
        }
    }

    $rosterCompare = (Get-OneFunction -Ast $dashboardAst -Name 'ConvertTo-WaiverRosterCompareHtml').Extent.Text
    foreach ($required in @(
        '[string]$PositionFocus = ""',
        'href="/waivers/roster-compare?candidate=$candidateHref&roster=$rosterHref$waiverPositionQuerySuffix"',
        'href="/waivers/compare?left=$candidateHref$waiverPositionQuerySuffix"',
        'href="/waivers/candidate/$candidateHref$waiverPositionBoardSuffix"',
        'href="/waivers$waiverPositionBoardSuffix">'
    )) {
        if ($rosterCompare.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-978 BLOCKED: Roster Compare position-continuity marker is missing: $required"
        }
    }

    $dashboard = [IO.File]::ReadAllText($dashboardPath)
    $routeCount = [regex]::Matches(
        $dashboard,
        [regex]::Escape('-PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1])')
    ).Count
    if ($routeCount -ne 4) {
        throw "BF-978 BLOCKED: expected four position-aware waiver renderer calls (Board, Candidate, Candidate Compare, Roster Compare); found $routeCount."
    }

    $positionFunction = (Get-OneFunction -Ast $dashboardAst -Name 'Get-WaiverPositionFocusFromRequestTarget').Extent.Text
    Invoke-Expression $positionFunction

    if ((Get-WaiverPositionFocusFromRequestTarget -RequestTarget '/waivers?position=wr') -cne 'WR') {
        throw 'BF-978 BLOCKED: position parser did not preserve a valid WR focus.'
    }
    if ((Get-WaiverPositionFocusFromRequestTarget -RequestTarget '/waivers?position=K') -cne '') {
        throw 'BF-978 BLOCKED: position parser accepted an unsupported position.'
    }
    if ((Get-WaiverPositionFocusFromRequestTarget -RequestTarget '/waivers') -cne '') {
        throw 'BF-978 BLOCKED: position parser invented a focus for an unfiltered request.'
    }

    $compareRequest = (Get-OneFunction -Ast $dashboardAst -Name 'Get-WaiverCompareRequest').Extent.Text
    if ($compareRequest.IndexOf('left = @()', [System.StringComparison]::Ordinal) -lt 0 -or
        $compareRequest.IndexOf('right = @()', [System.StringComparison]::Ordinal) -lt 0 -or
        $compareRequest.IndexOf('position = @()', [System.StringComparison]::Ordinal) -ge 0) {
        throw 'BF-978 BLOCKED: Candidate Compare exact-ID request contract was widened instead of keeping position separate.'
    }

    $rosterRequest = (Get-OneFunction -Ast $dashboardAst -Name 'Get-WaiverRosterCompareRequest').Extent.Text
    if ($rosterRequest.IndexOf('candidate = @()', [System.StringComparison]::Ordinal) -lt 0 -or
        $rosterRequest.IndexOf('roster = @()', [System.StringComparison]::Ordinal) -lt 0 -or
        $rosterRequest.IndexOf('position = @()', [System.StringComparison]::Ordinal) -ge 0) {
        throw 'BF-978 BLOCKED: Roster Compare exact-ID request contract was widened instead of keeping position separate.'
    }

    $core = [IO.File]::ReadAllText($corePath)
    $proxyMarker = '$dashboardRequestTarget = if ($path -eq "/waivers" -or $path -eq "/waivers/compare" -or $path -eq "/waivers/roster-compare" -or $candidate) { $parts[1] } else { $path }'
    if ($core.IndexOf($proxyMarker, [System.StringComparison]::Ordinal) -lt 0) {
        throw 'BF-978 BLOCKED: app-shell candidate-detail query preservation is missing.'
    }

    $transformText = [IO.File]::ReadAllText($transformPath)
    if ($transformText -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-978 BLOCKED: transform introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
    }

    Write-Host 'BF-978 WAIVER POSITION CONTEXT CONTINUITY ACCEPTANCE: PASS'
    Write-Host 'Coverage: Board -> Candidate -> Candidate Compare <-> Roster Compare -> focused returns'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
