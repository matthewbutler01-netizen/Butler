Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf987-weekly-attention-return-loop-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-987 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf987-attention-return-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $dashboardPath = Join-Path $root 'butler-dashboard.ps1'
    $corePath = Join-Path $root 'butler-app-shell-core-single.ps1'

    function Get-Ast {
        param([Parameter(Mandatory = $true)][string]$Path)
        $tokens = $null
        $errors = $null
        $ast = [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$errors)
        if (@($errors).Count -ne 0) {
            $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
            throw "BF-987 BLOCKED: staged file failed PowerShell parse: $Path :: $summary"
        }
        return $ast
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
            throw "BF-987 BLOCKED: expected one $Name function, found $($matches.Count)."
        }
        return $matches[0]
    }

    $coreAst = Get-Ast -Path $corePath
    $dashboardAst = Get-Ast -Path $dashboardPath

    $teamAttention = (Get-OneFunction -Ast $coreAst -Name 'Get-Bf979WeeklyAttentionHtml').Extent.Text
    $dashboardAttention = (Get-OneFunction -Ast $dashboardAst -Name 'Get-Bf979SnapshotWeeklyAttentionHtml').Extent.Text
    $originParser = (Get-OneFunction -Ast $dashboardAst -Name 'Get-Bf987WeeklyAttentionOriginFromRequestTarget').Extent.Text
    $board = (Get-OneFunction -Ast $dashboardAst -Name 'ConvertTo-WaiverHtml').Extent.Text
    $candidate = (Get-OneFunction -Ast $dashboardAst -Name 'ConvertTo-WaiverCandidateDetailHtml').Extent.Text
    $rosterCompare = (Get-OneFunction -Ast $dashboardAst -Name 'ConvertTo-WaiverRosterCompareHtml').Extent.Text

    Invoke-Expression $originParser

    $cases = @(
        @('/waivers?position=WR&roster=201&from=dashboard', 'dashboard'),
        @('/waivers?position=WR&roster=201&from=team', 'team'),
        @('/waivers?position=WR&roster=201&from=Dashboard', 'dashboard'),
        @('/waivers?position=WR&roster=201&from=https%3A%2F%2Fevil.example', ''),
        @('/waivers?position=WR&roster=201&from=dashboard&from=team', ''),
        @('/waivers?position=WR&roster=201', '')
    )
    foreach ($case in $cases) {
        $actual = Get-Bf987WeeklyAttentionOriginFromRequestTarget -RequestTarget ([string]$case[0])
        if ([string]$actual -cne [string]$case[1]) {
            throw "BF-987 BLOCKED: origin parser mismatch for $($case[0]); expected '$($case[1])', got '$actual'."
        }
    }

    foreach ($spec in @(
        @($teamAttention, 'id=`"weekly-attention`"', 'My Team attention anchor'),
        @($teamAttention, '&amp;from=team', 'My Team origin token'),
        @($dashboardAttention, 'id=`"weekly-attention`"', 'Dashboard attention anchor'),
        @($dashboardAttention, '&amp;from=dashboard', 'Dashboard origin token')
    )) {
        if ([string]$spec[0] -notmatch [regex]::Escape([string]$spec[1])) {
            throw "BF-987 BLOCKED: $($spec[2]) is missing."
        }
    }

    foreach ($spec in @(
        @($board, '[string]$AttentionOrigin = ""', 'Waiver Board origin parameter'),
        @($board, 'Back to Weekly Attention', 'Waiver Board direct return'),
        @($candidate, '[string]$AttentionOrigin = ""', 'Candidate Detail origin parameter'),
        @($candidate, 'Back to Weekly Attention', 'Candidate Detail direct return'),
        @($rosterCompare, '[string]$AttentionOrigin = ""', 'Roster Compare origin parameter'),
        @($rosterCompare, 'Back to Weekly Attention', 'Roster Compare direct return'),
        @($rosterCompare, '$attentionReturnHref = ''/#weekly-attention''', 'Dashboard fixed return target'),
        @($rosterCompare, '$attentionReturnHref = ''/team#weekly-attention''', 'My Team fixed return target')
    )) {
        if ([string]$spec[0] -notmatch [regex]::Escape([string]$spec[1])) {
            throw "BF-987 BLOCKED: $($spec[2]) is missing."
        }
    }

    $dashboardSource = [IO.File]::ReadAllText($dashboardPath)
    $originRouteCount = [regex]::Matches(
        $dashboardSource,
        [regex]::Escape('-AttentionOrigin (Get-Bf987WeeklyAttentionOriginFromRequestTarget -RequestTarget $parts[1])')
    ).Count
    if ($originRouteCount -ne 3) {
        throw "BF-987 BLOCKED: expected three deep-route origin handoffs, found $originRouteCount."
    }

    $surface = $originParser + [Environment]::NewLine + $board + [Environment]::NewLine + $candidate + [Environment]::NewLine + $rosterCompare
    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-987 BLOCKED: Weekly Attention return loop introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
    }

    Write-Host 'BF-987 WEEKLY ATTENTION RETURN LOOP ACCEPTANCE: PASS'
    Write-Host 'Coverage: dashboard/team origins are fixed-token only and survive Board -> Candidate -> held-starter compare -> exact Weekly Attention return'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
