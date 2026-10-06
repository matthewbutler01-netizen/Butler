Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workerPath = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
$teamTransformPath = Join-Path $PSScriptRoot 'butler-app-bf1019-v04-fantasy-manager-shell-transform.ps1'
$stagingPath = Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1'

foreach ($path in @($workerPath, $teamTransformPath, $stagingPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "BF-1019 BLOCKED: required source missing at $path"
    }
}

$worker = [System.IO.File]::ReadAllText($workerPath)
$teamTransform = [System.IO.File]::ReadAllText($teamTransformPath)
$staging = [System.IO.File]::ReadAllText($stagingPath)

$tokens = $null
$errors = $null
$workerAst = [System.Management.Automation.Language.Parser]::ParseInput($worker, [ref]$tokens, [ref]$errors)
if (@($errors).Count -gt 0) {
    throw 'BF-1019 BLOCKED: request worker parse failed.'
}

$accessibilityFunctions = @($workerAst.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'Add-ButlerAccessibility'
}, $true))
if ($accessibilityFunctions.Count -ne 1) {
    throw "BF-1019 BLOCKED: expected one Add-ButlerAccessibility function, found $($accessibilityFunctions.Count)."
}
. ([scriptblock]::Create($accessibilityFunctions[0].Extent.Text))

$fixture = '<!doctype html><html><head><title>Dashboard</title></head><body><main class="shell"><header class="top"><div class="target">Hard(CORE)-Dynasty &middot; nuke the whales | roster 6</div></header><nav class="nav" aria-label="Butler sections"><a class="active" href="/">Dashboard</a><a href="/team">My Team</a></nav><section class="panel"><h1>Dashboard</h1></section></main></body></html>'
$rendered = Add-ButlerAccessibility -Html $fixture

foreach ($marker in @(
    'class="manager-playbook"',
    '>LINEUP</div>',
    'href="/team">My Team</a>',
    'href="/matchup/autofill">Start/Sit Assistant</a>',
    'href="/matchup">Matchup</a>',
    'href="/autopilot">Auto-Pilot</a>',
    '>WAIVER</div>',
    'href="/waivers">Waiver Board</a>',
    '>TRADE</div>',
    'href="/trade">Trade Analyzer</a>',
    '>LEAGUE</div>',
    'href="/league">League</a>',
    '>TOOLS</div>',
    'href="/compare">Player Compare</a>',
    'href="/history?load=1">History</a>'
)) {
    if ($rendered.IndexOf($marker, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1019 BLOCKED: shared Playbook marker missing: $marker"
    }
}
if ([regex]::Matches($rendered, 'aria-current="page"').Count -ne 1) {
    throw 'BF-1019 BLOCKED: Dashboard shared Playbook did not expose exactly one current page.'
}

$lineupFixture = '<!doctype html><html><head><title>Lineup</title></head><body><main class="shell"><header class="top"><div class="target">Hard(CORE)-Dynasty &middot; nuke the whales | roster 6</div></header><nav class="nav" aria-label="Butler sections"><a href="/">Dashboard</a><a class="active" href="/matchup">Matchup</a></nav><section class="panel"><div class="eyebrow">Lineup advisor</div><h1>Lineup Review</h1></section></main></body></html>'
$lineupRendered = Add-ButlerAccessibility -Html $lineupFixture
if ($lineupRendered.IndexOf('class="playbook-start-sit" href="/matchup/autofill">Start/Sit Assistant</a>', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1019 BLOCKED: direct Start/Sit Assistant Playbook entry is missing.'
}
$lineupCurrent = [regex]::Matches($lineupRendered, '<a\b[^>]*aria-current="page"[^>]*>([^<]+)</a>')
if ($lineupCurrent.Count -ne 1 -or $lineupCurrent[0].Groups[1].Value -cne 'Matchup') {
    throw 'BF-1019 BLOCKED: legacy Matchup route identity was changed by the Start/Sit navigation entry.'
}

foreach ($marker in @(
    'function Get-V04AutoPilotHtml',
    '<title>Butler - Auto-Pilot</title>',
    'AUTOMATION OFF',
    'Open Start/Sit Assistant',
    'Auto-Pilot currently monitors and explains only.',
    'if ($path -eq ''/autopilot'')',
    'if ($requestTarget -cne ''/autopilot'')'
)) {
    if ($worker.IndexOf($marker, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1019 BLOCKED: Auto-Pilot entry marker missing: $marker"
    }
}

foreach ($marker in @(
    'Start/Sit Assistant',
    'href="/autopilot">Auto-Pilot</a>',
    '<div class="rail-label">WAIVER</div>',
    '<div class="rail-label">TRADE</div>',
    '<div class="rail-label">LEAGUE</div>',
    '<div class="rail-label">TOOLS</div>',
    'BF-1019 v0.4 fantasy-manager shell applied.'
)) {
    if ($teamTransform.IndexOf($marker, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-1019 BLOCKED: My Team v0.4 rail marker missing: $marker"
    }
}

if ($staging.IndexOf('bf1019Transform -CorePath $stagedCore', [System.StringComparison]::Ordinal) -lt 0) {
    throw 'BF-1019 BLOCKED: v0.4 My Team shell is not staged after the v0.3 closeout.'
}

$autoPilotStart = $worker.IndexOf('function Get-V04AutoPilotHtml', [System.StringComparison]::Ordinal)
$autoPilotEnd = $worker.IndexOf('function Send-HttpResponse', $autoPilotStart, [System.StringComparison]::Ordinal)
$autoPilotSurface = $worker.Substring($autoPilotStart, $autoPilotEnd - $autoPilotStart)

# The My Team transform enforces the same safety boundary against the actual
# generated rail/CSS surface. Do not scan the transform source itself here:
# its fail-closed assertion intentionally contains the forbidden-token names.
if ($autoPilotSurface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-1019 BLOCKED: Auto-Pilot entry introduced provider, optimizer, or write behavior.'
}

Write-Host 'BF-1019 V0.4 FANTASY MANAGER SHELL ACCEPTANCE: PASS'
Write-Host 'Coverage: grouped Playbook navigation, direct Start/Sit Assistant entry, read-only Auto-Pilot control surface, My Team parity, and no new write behavior.'
