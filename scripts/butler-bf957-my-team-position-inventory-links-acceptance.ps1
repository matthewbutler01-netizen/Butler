Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf957-team-' + [guid]::NewGuid().ToString('N'))
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
        throw 'BF-957 BLOCKED: staged core has parse errors.'
    }

    $function = $ast.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-TeamHtml'
    }, $true)
    if ($null -eq $function) {
        throw 'BF-957 BLOCKED: staged My Team renderer is missing.'
    }

    $team = $function.Extent.Text
    foreach ($required in @(
        'href="/players?q=QB"',
        'href="/players?q=RB"',
        'href="/players?q=WR"',
        'href="/players?q=TE"',
        'aria-label="Browse QB players"',
        'aria-label="Browse RB players"',
        'aria-label="Browse WR players"',
        'aria-label="Browse TE players"',
        '<small>Browse</small>',
        'href="#roster-starters"',
        'href="#roster-bench"',
        'href="#roster-reserve"'
    )) {
        if ($team.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-957 BLOCKED: staged position inventory marker is missing: $required"
        }
    }

    foreach ($requiredCss in @(
        'BF-957 My Team position inventory links',
        'a.roster-inventory-card{text-decoration:none',
        '.roster-position-link{display:grid',
        '.roster-position-link small{'
    )) {
        if ($core.IndexOf($requiredCss, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-957 BLOCKED: staged position inventory CSS marker is missing: $requiredCss"
        }
    }

    $surfaceStart = $team.IndexOf('$qbRosterCount = @($Roster.Players', [System.StringComparison]::Ordinal)
    $surfaceEnd = $team.IndexOf('$autoFillHtml = ConvertTo-AutoFillHtml -AutoFill $AutoFill', $surfaceStart, [System.StringComparison]::Ordinal)
    if ($surfaceStart -lt 0 -or $surfaceEnd -le $surfaceStart) {
        throw 'BF-957 BLOCKED: position inventory surface could not be isolated.'
    }
    $surface = $team.Substring($surfaceStart, $surfaceEnd - $surfaceStart)
    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
        throw 'BF-957 BLOCKED: position inventory surface introduced backend, provider, optimizer, or write behavior.'
    }

    Write-Host 'BF-957 MY TEAM POSITION INVENTORY LINKS ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
