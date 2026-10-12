param([switch]$BrokenCss, [switch]$BusyGate)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# BF-1136: opening Auto-Pilot must not depend on unrelated TradeHost,
# TradeLab or PlayerDetail modules. Run in a fresh PowerShell process.
# This fixture serves no league data and points core reads to closed port 9.
$shared = Join-Path $PSScriptRoot 'butler-bf1127-worker-first-open-503-acceptance.ps1'
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    $shared, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) { throw 'BF-1136 BLOCKED: shared loopback fixture does not parse.' }
$helper = $ast.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -ceq 'Invoke-Bf1127FirstOpenGet'
}, $true)
if ($null -eq $helper) { throw 'BF-1136 BLOCKED: actual TCP worker helper missing.' }
. ([scriptblock]::Create($helper.Extent.Text))

$repoRoot = Split-Path -Parent $PSScriptRoot
$cachePath = Join-Path $PSScriptRoot 'butler-app-request-worker-cache.ps1'
$privateMarker = 'BF1136_PRIVATE_NO_LEAGUE'
$root = Join-Path ([IO.Path]::GetTempPath()) ('butler-bf1136-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root -Force | Out-Null
$missingTrade = Join-Path $root 'missing-trade.ps1'
$readOnlyModules = Join-Path $root 'read-only-ui.ps1'
# The shared TCP helper also feeds its TradeLab, Detail and Refresh paths
# through $validPath. Keep all of them on this one safe synthetic stub.
$validPath = $readOnlyModules
# These dummy helpers intentionally force no automatic write or provider
# fetch. The production worker's Auto-Pilot watch still has to render a
# source-unavailable state without invoking any Trade or Detail module.
$moduleText = @'
function Get-AppCss { return 'body{font-family:Arial,sans-serif}' }
function Add-AutomaticAutoPilotRefresh {
    param([string]$Html,[string]$RequestTarget,[string]$DashboardHtml,[string]$Token)
    return @{ Html = $Html; Nonce = '' }
}
'@
if ($BrokenCss) {
    # BF-1138: a throwing UI renderer must not reset the connection or
    # reveal the dummy private league marker/exception in an error page.
    $moduleText = $moduleText.Replace(
        "return 'body{font-family:Arial,sans-serif}'",
        "throw 'BF1136_PRIVATE_NO_LEAGUE'")
}
[IO.File]::WriteAllText($readOnlyModules, $moduleText, [Text.Encoding]::ASCII)

if ($BrokenCss -and $BusyGate) {
    throw 'BF-1143 BLOCKED: broken CSS and busy-gate scenarios must run independently.'
}
$occupiedGate = $null
$heldSlots = 0
try {
    # BF-1143: hold both real named companion slots while the actual
    # public Auto-Pilot HTTP worker renders. An offline/contended core
    # must produce a safe read-only page instead of waiting three minutes.
    if ($BusyGate) {
        $gateName = 'Local\Butler.Companion.Heavy.{0}' -f $PID
        $occupiedGate = [System.Threading.Semaphore]::new(2, 2, $gateName)
        for ($slot = 0; $slot -lt 2; $slot++) {
            if (-not $occupiedGate.WaitOne(0)) {
                throw 'BF-1143 BLOCKED: unable to occupy the synthetic companion gate.'
            }
            $heldSlots++
        }
    }
    $openTimer = [System.Diagnostics.Stopwatch]::StartNew()
    $wire = Invoke-Bf1127FirstOpenGet -Target '/autopilot' `
        -HistoryPath $readOnlyModules -TradeHostPath $missingTrade
    $openTimer.Stop()
    if ($BusyGate -and ($openTimer.ElapsedMilliseconds -lt 5500 -or
        $openTimer.ElapsedMilliseconds -gt 20000)) {
        throw 'BF-1143 BLOCKED: busy public Auto-Pilot ignored the bounded two-read companion wait.'
    }

    if ($BusyGate -and (
        $wire.IndexOf('LOCAL DATA BUSY', [StringComparison]::Ordinal) -lt 0 -or
        $wire.IndexOf('data-butler-lineup-reason="local-data-busy"', [StringComparison]::Ordinal) -lt 0 -or
        $wire.IndexOf('href="/autopilot">Retry Auto-Pilot</a>', [StringComparison]::Ordinal) -lt 0)) {
        throw 'BF-1144 BLOCKED: busy Auto-Pilot failed to explain its safe retry without source claims.'
    }
    if ($BrokenCss) {
        if (-not $wire.StartsWith('HTTP/1.1 500 Internal Server Error', [StringComparison]::Ordinal) -or
            $wire.IndexOf('X-Butler-Failure-Layer: PUBLIC_WORKER', [StringComparison]::Ordinal) -lt 0 -or
            $wire.IndexOf('<h1>Auto-Pilot unavailable</h1>', [StringComparison]::Ordinal) -lt 0 -or
            $wire.IndexOf('<a href="/autopilot">Try Auto-Pilot again</a>', [StringComparison]::Ordinal) -lt 0 -or
            $wire.IndexOf('No Sleeper transaction was submitted by this page.', [StringComparison]::Ordinal) -lt 0 -or
            $wire.IndexOf('Cache-Control: no-store', [StringComparison]::Ordinal) -lt 0 -or
            $wire.IndexOf('Content-Length:', [StringComparison]::Ordinal) -lt 0 -or
            $wire.IndexOf($privateMarker, [StringComparison]::Ordinal) -ge 0 -or
            $wire.IndexOf($root, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $wire.Length -gt 16000) {
            throw 'BF-1138 BLOCKED: Auto-Pilot CSS failure did not return one safe HTTP 500.'
        }
    }
    else {
    if (-not $wire.StartsWith('HTTP/1.1 200 OK', [StringComparison]::Ordinal) -or
        $wire.IndexOf('CURRENT WEEKLY WATCH', [StringComparison]::Ordinal) -lt 0 -or
        $wire.IndexOf('Auto-Pilot', [StringComparison]::Ordinal) -lt 0 -or
        $wire.IndexOf('READ ONLY PREVIEW', [StringComparison]::Ordinal) -lt 0 -or
        $wire.IndexOf('does not currently run background monitoring or submit a Sleeper transaction', [StringComparison]::OrdinalIgnoreCase) -lt 0 -or
        $wire.IndexOf('Content-Type: text/html', [StringComparison]::Ordinal) -lt 0 -or
        $wire.IndexOf('Cache-Control: no-store', [StringComparison]::Ordinal) -lt 0 -or
        $wire.IndexOf('X-Butler-Failure-Layer:', [StringComparison]::Ordinal) -ge 0 -or
        $wire.IndexOf($privateMarker, [StringComparison]::Ordinal) -ge 0 -or
        $wire.IndexOf($root, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
        $wire.Length -gt 90000) {
        throw 'BF-1136 BLOCKED: Auto-Pilot depended on Trade/Detail or did not render safely.'
    }
    # No core data means no actionable approvals, even if the route loads.
    if (-not $BusyGate -and
        $wire.IndexOf('LOCAL DATA BUSY', [StringComparison]::Ordinal) -ge 0) {
        throw 'BF-1144 BLOCKED: ordinary disconnected core was mislabeled as semaphore contention.'
    }
    if ($wire.IndexOf('READY FOR MANAGER REVIEW', [StringComparison]::Ordinal) -ge 0 -or
        $wire.IndexOf('NO ACTION REQUIRED', [StringComparison]::Ordinal) -ge 0) {
        throw 'BF-1136 BLOCKED: Auto-Pilot rendered actionable approval without source evidence.'
    }
    }
}
finally {
    if ($null -ne $occupiedGate) {
        for ($slot = 0; $slot -lt $heldSlots; $slot++) {
            [void]$occupiedGate.Release()
        }
        $occupiedGate.Dispose()
    }
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
if ($BusyGate) {
    Write-Host 'BF-1143/1144 PASS: bounded real HTTP Auto-Pilot contention is safely explained.'
}
elseif ($BrokenCss) {
    Write-Host 'BF-1138 PASS: broken Auto-Pilot renderer returns safe HTTP 500 and no private error.'
}
else {
    Write-Host 'BF-1136 PASS: Auto-Pilot loads without Trade/Detail, and core outage stays non-actionable.'
}
