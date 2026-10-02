Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf996-league-manager-orientation-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-996 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf996-league-orientation-' + [guid]::NewGuid().ToString('N'))
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
        throw "BF-996 BLOCKED: staged core failed PowerShell parse: $summary"
    }

    $leagueMatches = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-LeagueHtml'
    }, $true))
    if ($leagueMatches.Count -ne 1) {
        throw "BF-996 BLOCKED: expected one ConvertTo-LeagueHtml function, found $($leagueMatches.Count)."
    }
    $league = $leagueMatches[0].Extent.Text

    foreach ($required in @(
        '<div class="eyebrow">Manager orientation</div>',
        'Your team and the league have different jobs here',
        '<span class="eyebrow">Your franchise</span>',
        'Roster, lineup review, Weekly Attention, and position inventory stay in your dedicated team workspace.',
        'href="/team">Open My Team</a>',
        '<span class="eyebrow">League teams</span>',
        'The franchise board is league-neutral.',
        'Butler does not guess that every displayed team is an opponent.',
        'href="#league-franchise-board">View franchise board</a>',
        'id="league-franchise-board"',
        'This is a neutral league snapshot and can include your own franchise.',
        'Each displayed card keeps its exact team identity for Scout franchise and Trade Analyzer.',
        '$leaderHrefId = [System.Uri]::EscapeDataString([string]$leader.TeamId)',
        'href="/franchise?id=$leaderHrefId">Scout franchise</a>',
        'href="/trade?opponent=$leaderHrefId">Open Trade Analyzer</a>'
    )) {
        if ($league.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-996 BLOCKED: staged League Hub marker is missing: $required"
        }
    }

    $coreText = [IO.File]::ReadAllText($corePath)
    foreach ($required in @(
        'BF-996 League manager orientation.',
        '.league-role-grid{',
        '.league-role-card{',
        '.league-role-owned{',
        '#league-franchise-board{',
        '@media(max-width:760px)'
    )) {
        if ($coreText.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-996 BLOCKED: League orientation CSS marker is missing: $required"
        }
    }

    $exactTradeCount = [regex]::Matches($league, [regex]::Escape('href="/trade?opponent=$leaderHrefId">Open Trade Analyzer</a>')).Count
    if ($exactTradeCount -ne 1) {
        throw "BF-996 BLOCKED: expected one exact franchise-card Trade Analyzer action, found $exactTradeCount."
    }

    $surface = $league
    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-996 BLOCKED: League manager orientation introduced provider, backend-read, optimizer, write, or open-redirect behavior.'
    }

    Write-Host 'BF-996 LEAGUE MANAGER ORIENTATION ACCEPTANCE: PASS'
    Write-Host 'Coverage: clear My Team vs league-team roles, honest neutral-board identity boundary, exact Scout/Trade partner context, responsive presentation, and no new reads or writes'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
