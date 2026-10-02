Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$tradeHost = Join-Path $PSScriptRoot 'butler-trade-lab-host.ps1'
$tradeLab = Join-Path $PSScriptRoot 'butler-trade-lab.ps1'
$history = Join-Path $PSScriptRoot 'butler-decision-history.ps1'

foreach ($path in @($tradeHost, $tradeLab, $history)) {
    $tokens = $null
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -ne 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-971 BLOCKED: companion module failed PowerShell parse: $path :: $summary"
    }
}

# Match the production app-shell load order that determines the winning shared functions.
. $tradeHost
. $tradeLab
. $history

$historyNav = Get-AppNav -Active 'history'
$tradeNav = Get-AppNav -Active 'trade'

foreach ($nav in @($historyNav, $tradeNav)) {
    foreach ($required in @(
        'href="/">Dashboard</a>',
        'href="/team">My Team</a>',
        'href="/matchup">Matchup</a>',
        'href="/waivers">Waiver Board</a>',
        'href="/players">Player Search</a>',
        'href="/compare">Player Compare</a>',
        'href="/league">League</a>',
        'href="/trade">Trade Analyzer</a>',
        'href="/history?load=1">History</a>'
    )) {
        if ($nav.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-971 BLOCKED: loaded companion navigation marker is missing: $required"
        }
    }

    $linkCount = [regex]::Matches($nav, '<a').Count
    if ($linkCount -ne 9) {
        throw "BF-971 BLOCKED: loaded companion navigation must contain exactly nine manager destinations; found $linkCount."
    }

    if ($nav -match 'href="/history">') {
        throw 'BF-971 BLOCKED: loaded companion navigation still contains the stale unloaded History route.'
    }
}

if ($historyNav.IndexOf('<a class="active" href="/history?load=1">History</a>', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-971 BLOCKED: History is not active in the final loaded companion navigation.'
}
if ($tradeNav.IndexOf('<a class="active" href="/trade">Trade Analyzer</a>', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-971 BLOCKED: Trade Analyzer is not active in the final loaded companion navigation.'
}

$css = Get-AppCss
foreach ($required in @(
    'BF-971 companion mobile manager navigation parity',
    'flex-wrap:nowrap!important',
    'overflow-x:auto',
    'scroll-snap-type:x proximity',
    'scroll-snap-align:start'
)) {
    if ($css.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-971 BLOCKED: companion mobile navigation marker is missing: $required"
    }
}

$oldNav = '<nav class="nav" aria-label="Butler sections"><a href="/">Dashboard</a><a href="/team">My Team</a><a href="/matchup">Matchup</a><a href="/waivers">Waiver Board</a><a href="/league">League</a><a href="/trade">Trade Analyzer</a><a href="/history">History</a></nav>'
foreach ($normalizer in @('trade','history')) {
    $upgraded = if ($normalizer -ceq 'trade') {
        Add-TradeNavigation -Html $oldNav
    }
    else {
        Add-AppNavigation -Html $oldNav
    }

    if ($upgraded.IndexOf('href="/history?load=1">History</a>', [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-971 BLOCKED: $normalizer companion navigation did not upgrade the old History route."
    }
    if ($upgraded -match 'href="/history">') {
        throw "BF-971 BLOCKED: $normalizer companion navigation retained the stale History route."
    }
}

$duplicateNav = '<nav class="nav" aria-label="Butler sections"><a href="/">Dashboard</a><a href="/team">My Team</a><a href="/matchup">Matchup</a><a href="/waivers">Waiver Board</a><a href="/league">League</a><a href="/trade">Trade Analyzer</a><a href="/history?load=1">History</a><a href="/history">History</a></nav>'
foreach ($normalizer in @('trade','history')) {
    $deduped = if ($normalizer -ceq 'trade') {
        Add-TradeNavigation -Html $duplicateNav
    }
    else {
        Add-AppNavigation -Html $duplicateNav
    }

    $directCount = [regex]::Matches($deduped, [regex]::Escape('href="/history?load=1">History</a>')).Count
    if ($directCount -ne 1) {
        throw "BF-971 BLOCKED: $normalizer companion navigation must keep exactly one direct-loaded History link; found $directCount."
    }
    if ($deduped -match 'href="/history">') {
        throw "BF-971 BLOCKED: $normalizer companion navigation did not remove the duplicate stale History link."
    }
}

$tradeText = [IO.File]::ReadAllText($tradeHost)
$historyText = [IO.File]::ReadAllText($history)
foreach ($text in @($tradeText, $historyText)) {
    if ($text -match 'submitTransaction|setFaab|Method = "POST"|https://api\.sleeper\.app') {
        throw 'BF-971 BLOCKED: companion navigation source contains a forbidden transaction/provider-write marker.'
    }
}

Write-Host 'BF-971 COMPANION NAVIGATION PARITY ACCEPTANCE: PASS'
