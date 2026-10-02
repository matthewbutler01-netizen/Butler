Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf955-team-' + [guid]::NewGuid().ToString('N'))
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
        throw 'BF-955 BLOCKED: staged core has parse errors.'
    }

    $function = $ast.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-TeamHtml'
    }, $true)
    if ($null -eq $function) {
        throw 'BF-955 BLOCKED: staged My Team renderer is missing.'
    }

    $team = $function.Extent.Text
    foreach ($required in @(
        '$reserveTaxiCount = [int]$Roster.ReserveCount + [int]$Roster.TaxiCount',
        'aria-label="Roster slot distribution"',
        '<strong>Roster slots</strong>',
        '<span>Starters</span>',
        '$(ConvertTo-HtmlText $Roster.StarterCount)',
        '<span>Bench</span>',
        '$(ConvertTo-HtmlText $Roster.BenchCount)',
        '<span>Reserve / taxi</span>',
        'Reserve $(ConvertTo-HtmlText $Roster.ReserveCount)',
        'Taxi $(ConvertTo-HtmlText $Roster.TaxiCount)',
        '$corePositionInventoryHtml$rosterStateInventoryHtml'
    )) {
        if ($team.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-955 BLOCKED: staged roster-slot marker is missing: $required"
        }
    }

    foreach ($requiredCss in @(
        'BF-955 My Team roster slot summary',
        '.roster-state-summary{',
        '@media(max-width:760px){.roster-state-summary'
    )) {
        if ($core.IndexOf($requiredCss, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-955 BLOCKED: staged roster-slot CSS marker is missing: $requiredCss"
        }
    }

    if ($team -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
        throw 'BF-955 BLOCKED: My Team roster slot summary introduced backend, provider, optimizer, or write behavior.'
    }

    Write-Host 'BF-955 MY TEAM ROSTER SLOT SUMMARY ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
