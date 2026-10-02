Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf958-team-' + [guid]::NewGuid().ToString('N'))
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
        throw 'BF-958 BLOCKED: staged core has parse errors.'
    }

    $function = $ast.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-TeamHtml'
    }, $true)
    if ($null -eq $function) {
        throw 'BF-958 BLOCKED: staged My Team renderer is missing.'
    }

    $team = $function.Extent.Text
    foreach ($required in @(
        '<div class="rail-label">ON THIS PAGE</div>',
        'href="#team-roster">Roster</a>',
        'href="#team-lineup">Lineup advisor</a>',
        'href="#team-positions">Position outlook</a>',
        'href="#team-draft">Draft capital</a>',
        'id="team-roster" class="panel team-section-target" tabindex="-1"',
        'id="team-positions" class="panel team-section-target" tabindex="-1"',
        'id="team-draft" class="panel team-section-target" tabindex="-1"',
        '$autoFillHtml = ConvertTo-AutoFillHtml -AutoFill $AutoFill -Roster $Roster',
        '$autoFillHtml.Replace(',
        'id="team-lineup" class="panel recommendation-panel team-section-target" tabindex="-1"'
    )) {
        if ($team.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-958 BLOCKED: staged section-navigation marker is missing: $required"
        }
    }

    foreach ($target in @('team-roster','team-lineup','team-positions','team-draft')) {
        $hrefCount = [regex]::Matches($team, [regex]::Escape('href="#' + $target + '"')).Count
        $idCount = [regex]::Matches($team, [regex]::Escape('id="' + $target + '"')).Count
        if ($hrefCount -ne 1 -or $idCount -ne 1) {
            throw "BF-958 BLOCKED: section target $target must have exactly one rail link and one target (href=$hrefCount id=$idCount)."
        }
    }

    foreach ($requiredCss in @(
        'BF-958 My Team workspace section navigation',
        '.team-section-target{scroll-margin-top:20px}',
        '.team-rail .rail-jump{',
        '.team-rail .rail-jump::before{'
    )) {
        if ($core.IndexOf($requiredCss, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-958 BLOCKED: staged section-navigation CSS marker is missing: $requiredCss"
        }
    }

    $lineupAnchor = [regex]::Match(
        $team,
        [regex]::Escape('$autoFillHtml = $autoFillHtml.Replace(') + '.*?team-lineup.*?\)',
        [System.Text.RegularExpressions.RegexOptions]::Singleline
    ).Value
    if ([string]::IsNullOrWhiteSpace($lineupAnchor)) {
        throw 'BF-958 BLOCKED: lineup section anchor mutation could not be isolated.'
    }
    if ($lineupAnchor -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
        throw 'BF-958 BLOCKED: lineup section anchor introduced backend, provider, optimizer, or write behavior.'
    }

    Write-Host 'BF-958 MY TEAM SECTION NAVIGATION ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
