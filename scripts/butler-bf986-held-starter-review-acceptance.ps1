Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-dashboard-bf986-held-starter-review-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-986 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf986-held-starter-review-' + [guid]::NewGuid().ToString('N'))
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
        throw "BF-986 BLOCKED: staged Dashboard failed PowerShell parse: $summary"
    }

    $matches = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-WaiverRosterCompareHtml'
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-986 BLOCKED: expected one ConvertTo-WaiverRosterCompareHtml function, found $($matches.Count)."
    }

    $rosterText = $matches[0].Extent.Text

    foreach ($required in @(
        '$replacementReviewEyebrow = ''Waiver Roster Compare''',
        '$replacementReviewHeadline = ''Candidate vs roster context''',
        '$replacementReviewContext = ''''',
        'if ($replacementComparisonActive -and $null -ne $replacementComparedRoster)',
        '$replacementReviewEyebrow = ''Held starter replacement review''',
        '$replacementReviewHeadline = ''Candidate vs held starter''',
        'Review one exact authorized waiver candidate against the exact verified starter carried from Weekly Attention.',
        'This exact starter is the replacement context carried into this comparison. No replacement has been selected.',
        '$replacementReviewContext$replacementComparisonActions'
    )) {
        if ($rosterText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-986 BLOCKED: staged replacement-review marker is missing: $required"
        }
    }

    foreach ($required in @(
        '$replacementComparisonActive = $false',
        '[string]$replacementComparedRoster.RosterSlot -ceq ''STARTER''',
        '$replacementComparisonActions = Convert-Bf985ReplacementComparisonActions'
    )) {
        if ($rosterText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-986 BLOCKED: BF-985 replacement focus regressed: $required"
        }
    }

    if ($rosterText -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-986 BLOCKED: replacement review presentation introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
    }

    Write-Host 'BF-986 HELD-STARTER REPLACEMENT REVIEW ACCEPTANCE: PASS'
    Write-Host 'Coverage: exact replacement comparison labels the held starter and preserves neutral read-only decision context'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
