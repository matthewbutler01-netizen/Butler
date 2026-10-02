Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf956-team-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $corePath = Join-Path $root 'butler-app-shell-core-single.ps1'
    $core = [IO.File]::ReadAllText($corePath)
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($corePath, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -ne 0) {
        throw 'BF-956 BLOCKED: staged core has parse errors.'
    }

    $function = $ast.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-TeamHtml'
    }, $true)
    if ($null -eq $function) {
        throw 'BF-956 BLOCKED: staged My Team renderer is missing.'
    }

    $team = $function.Extent.Text
    foreach ($required in @(
        'href="#roster-starters"',
        'href="#roster-bench"',
        'href="#roster-reserve"',
        'id="roster-starters" class="roster-group" tabindex="-1"',
        'id="roster-bench" class="roster-group" tabindex="-1"',
        'id="roster-reserve" class="roster-group" tabindex="-1"',
        '<h3>Starting lineup</h3>',
        '<h3>Bench</h3>',
        '<h3>Reserve &amp; taxi</h3>'
    )) {
        if ($team.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-956 BLOCKED: staged roster jump marker is missing: $required"
        }
    }

    foreach ($requiredCss in @(
        'BF-956 My Team roster jump navigation',
        'a.roster-state-item{text-decoration:none',
        '.roster-group{scroll-margin-top:20px}',
        '.roster-group:focus{'
    )) {
        if ($core.IndexOf($requiredCss, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-956 BLOCKED: staged roster jump CSS marker is missing: $requiredCss"
        }
    }

    $jumpSurface = [regex]::Match($team, '(?s)<div class="roster-state-summary".*?</div>\s*<div class="roster-board">').Value
    if ([string]::IsNullOrWhiteSpace($jumpSurface)) {
        throw 'BF-956 BLOCKED: roster jump surface could not be isolated.'
    }
    if ($jumpSurface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
        throw 'BF-956 BLOCKED: roster jump surface introduced backend, provider, optimizer, or write behavior.'
    }

    Write-Host 'BF-956 MY TEAM ROSTER JUMP ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
