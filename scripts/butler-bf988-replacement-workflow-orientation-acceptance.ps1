Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-dashboard-bf988-replacement-workflow-orientation-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-988 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf988-replacement-orientation-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $dashboardPath = Join-Path $root 'butler-dashboard.ps1'
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($dashboardPath, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -ne 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-988 BLOCKED: staged Dashboard failed PowerShell parse: $summary"
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
            throw "BF-988 BLOCKED: expected one $Name function, found $($matches.Count)."
        }
        return $matches[0]
    }

    $board = (Get-OneFunction -Ast $ast -Name 'ConvertTo-WaiverHtml').Extent.Text
    $candidate = (Get-OneFunction -Ast $ast -Name 'ConvertTo-WaiverCandidateDetailHtml').Extent.Text
    $roster = (Get-OneFunction -Ast $ast -Name 'ConvertTo-WaiverRosterCompareHtml').Extent.Text

    foreach ($spec in @(
        @($board, 'if ($null -ne $replacementRosterPlayer)', 'Board replacement guard'),
        @($board, 'Step 1 of 3: Choose candidate', 'Board workflow step'),
        @($candidate, 'if (-not [string]::IsNullOrWhiteSpace($encodedRosterFocus))', 'Candidate replacement guard'),
        @($candidate, 'Step 2 of 3: Review candidate', 'Candidate workflow step'),
        @($roster, 'if ($replacementComparisonActive)', 'Roster comparison guard'),
        @($roster, 'Step 3 of 3: Compare to held starter', 'Roster workflow step'),
        @($roster, 'Back to Weekly Attention', 'BF-987 return loop regression'),
        @($roster, '$replacementReviewContext', 'BF-986 review context regression')
    )) {
        if ([string]$spec[0] -notmatch [regex]::Escape([string]$spec[1])) {
            throw "BF-988 BLOCKED: $($spec[2]) is missing."
        }
    }

    $allSteps = $board + [Environment]::NewLine + $candidate + [Environment]::NewLine + $roster
    foreach ($step in @(
        'Step 1 of 3: Choose candidate',
        'Step 2 of 3: Review candidate',
        'Step 3 of 3: Compare to held starter'
    )) {
        $count = [regex]::Matches($allSteps, [regex]::Escape($step)).Count
        if ($count -ne 1) {
            throw "BF-988 BLOCKED: expected one workflow label '$step', found $count."
        }
    }

    if ($allSteps -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-988 BLOCKED: replacement workflow orientation introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
    }

    Write-Host 'BF-988 REPLACEMENT WORKFLOW ORIENTATION ACCEPTANCE: PASS'
    Write-Host 'Coverage: exact replacement mode shows a three-step Board -> Candidate -> held-starter compare path without changing normal waiver traffic'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
