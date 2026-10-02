Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf959-waivers-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $dashboardPath = Join-Path $root 'butler-dashboard.ps1'
    $dashboard = [IO.File]::ReadAllText($dashboardPath)
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($dashboardPath, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -ne 0) {
        throw 'BF-959 BLOCKED: staged Dashboard has parse errors.'
    }

    $function = $ast.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-WaiverHtml'
    }, $true)
    if ($null -eq $function) {
        throw 'BF-959 BLOCKED: staged Waiver Board renderer is missing.'
    }

    $waiver = $function.Extent.Text
    foreach ($required in @(
        '$waiverQuickActions = ''''',
        '$pair.Active -and [string]$pair.AddSleeperId -match ''^[0-9]+
        '$waiverAddHrefId = [System.Uri]::EscapeDataString([string]$pair.AddSleeperId)',
        'href="/waivers/candidate/',
        '">Open governed ADD</a>',
        'href="/waivers/roster-compare?candidate=',
        '">Compare ADD to roster</a>',
        '$waiverQuickActions$waiverHistoryLink',
        'BF-959 Waiver decision quick actions'
    )) {
        if ($waiver.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0 -and
            $dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-959 BLOCKED: staged waiver quick-action marker is missing: $required"
        }
    }

    $surfaceStart = $waiver.IndexOf('$waiverQuickActions = ''''', [System.StringComparison]::Ordinal)
    $surfaceEnd = $waiver.IndexOf('return @"', $surfaceStart, [System.StringComparison]::Ordinal)
    if ($surfaceStart -lt 0 -or $surfaceEnd -le $surfaceStart) {
        throw 'BF-959 BLOCKED: waiver quick-action surface could not be isolated.'
    }
    $surface = $waiver.Substring($surfaceStart, $surfaceEnd - $surfaceStart)
    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
        throw 'BF-959 BLOCKED: Waiver quick-action surface introduced provider, optimizer, FAAB, or write behavior.'
    }

    Write-Host 'BF-959 WAIVER DECISION QUICK ACTIONS ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
'',
        '$waiverAddHrefId = [System.Uri]::EscapeDataString([string]$pair.AddSleeperId)',
        'href="/waivers/candidate/',
        '">Open governed ADD</a>',
        'href="/waivers/roster-compare?candidate=',
        '">Compare ADD to roster</a>',
        '$waiverQuickActions$waiverHistoryLink',
        'BF-959 Waiver decision quick actions'
    )) {
        if ($waiver.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0 -and
            $dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-959 BLOCKED: staged waiver quick-action marker is missing: $required"
        }
    }

    $surfaceStart = $waiver.IndexOf('$waiverQuickActions = ''''', [System.StringComparison]::Ordinal)
    $surfaceEnd = $waiver.IndexOf('return @"', $surfaceStart, [System.StringComparison]::Ordinal)
    if ($surfaceStart -lt 0 -or $surfaceEnd -le $surfaceStart) {
        throw 'BF-959 BLOCKED: waiver quick-action surface could not be isolated.'
    }
    $surface = $waiver.Substring($surfaceStart, $surfaceEnd - $surfaceStart)
    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
        throw 'BF-959 BLOCKED: Waiver quick-action surface introduced provider, optimizer, FAAB, or write behavior.'
    }

    Write-Host 'BF-959 WAIVER DECISION QUICK ACTIONS ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
