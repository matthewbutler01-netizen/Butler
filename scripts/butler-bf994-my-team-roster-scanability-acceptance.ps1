Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf994-my-team-roster-scanability-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-994 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf994-my-team-roster-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $corePath = Join-Path $root 'butler-app-shell-core-single.ps1'
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($corePath, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -ne 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-994 BLOCKED: staged core failed PowerShell parse: $summary"
    }

    $matches = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-TeamHtml'
    }, $true))
    if ($matches.Count -ne 1) {
        throw "BF-994 BLOCKED: expected one ConvertTo-TeamHtml function, found $($matches.Count)."
    }

    $team = $matches[0].Extent.Text

    foreach ($required in @(
        '$weeklyAttentionRailHtml = if (-not [string]::IsNullOrWhiteSpace($weeklyAttentionHtml))',
        'href="#weekly-attention">Weekly attention</a>',
        'class="roster-group roster-group-starters"',
        'class="roster-group roster-group-bench"',
        'class="roster-group roster-group-reserve"',
        'aria-label="Starting lineup"',
        'aria-label="Bench"',
        'aria-label="Reserve and taxi"',
        'roster-group-count">$(ConvertTo-HtmlText $Roster.StarterCount)',
        'roster-group-count">$(ConvertTo-HtmlText $Roster.BenchCount)',
        'roster-group-count">$(ConvertTo-HtmlText $reserveTaxiCount)'
    )) {
        if ($team.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-994 BLOCKED: staged My Team marker is missing: $required"
        }
    }

    if ($team -notmatch '\$weeklyAttentionRailHtml\s*<a class="rail-jump" href="#team-roster">Roster</a>') {
        throw 'BF-994 BLOCKED: Weekly Attention must immediately precede the exact roster jump link.'
    }

    $coreText = [IO.File]::ReadAllText($corePath)
    foreach ($required in @(
        'BF-994 My Team roster scanability batch',
        '.roster-group-count{',
        '.roster-group-starters{',
        '.roster-group-bench{',
        '.roster-group-reserve{',
        '.team-rail .rail-attention{',
        '@media(max-width:760px)'
    )) {
        if ($coreText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-994 BLOCKED: roster scanability CSS marker is missing: $required"
        }
    }

    $cssMatches = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Get-AppCss'
    }, $true))
    if ($cssMatches.Count -ne 1) {
        throw "BF-994 BLOCKED: expected one Get-AppCss function, found $($cssMatches.Count)."
    }

    $surface = $team + [Environment]::NewLine + $cssMatches[0].Extent.Text
    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-994 BLOCKED: My Team roster scanability introduced provider, optimizer, write, or open-redirect behavior.'
    }

    Write-Host 'BF-994 MY TEAM ROSTER SCANABILITY ACCEPTANCE: PASS'
    Write-Host 'Coverage: conditional Weekly Attention navigation, visible starter/bench/reserve counts, distinct roster groups, and tighter mobile scanability without new reads or writes'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
