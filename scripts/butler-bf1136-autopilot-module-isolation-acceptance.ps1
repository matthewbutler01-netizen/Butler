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
[IO.File]::WriteAllText($readOnlyModules, $moduleText, [Text.Encoding]::ASCII)

try {
    $wire = Invoke-Bf1127FirstOpenGet -Target '/autopilot' `
        -HistoryPath $readOnlyModules -TradeHostPath $missingTrade

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
    if ($wire.IndexOf('READY FOR MANAGER REVIEW', [StringComparison]::Ordinal) -ge 0 -or
        $wire.IndexOf('NO ACTION REQUIRED', [StringComparison]::Ordinal) -ge 0) {
        throw 'BF-1136 BLOCKED: Auto-Pilot rendered actionable approval without source evidence.'
    }
}
finally {
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Host 'BF-1136 PASS: Auto-Pilot loads without Trade/Detail, and core outage stays non-actionable.'
