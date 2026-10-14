Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# BF-1130: the actual cached manager worker must return one usable,
# privacy-safe HTTP 500 if the inner Butler core cannot be contacted AND
# its normal styled error-page module is unavailable. Loopback only.
# Never invokes Sleeper, runs recovery, modifies a league or starts a core.
$repoRoot = Split-Path -Parent $PSScriptRoot
$cachePath = Join-Path $PSScriptRoot 'butler-app-request-worker-cache.ps1'
$fixturePath = Join-Path $PSScriptRoot 'butler-bf1127-worker-first-open-503-acceptance.ps1'
if (-not (Test-Path -LiteralPath $fixturePath -PathType Leaf)) {
    throw 'BF-1130 BLOCKED: exact loopback TCP fixture unavailable.'
}
$tokens = $null
$errors = $null
$fixtureAst = [System.Management.Automation.Language.Parser]::ParseFile(
    $fixturePath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    throw 'BF-1130 BLOCKED: TCP fixture does not parse on Windows PowerShell 5.1.'
}
$tcpHelper = $fixtureAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -ceq 'Invoke-Bf1127FirstOpenGet'
}, $true)
if ($null -eq $tcpHelper) { throw 'BF-1130 BLOCKED: TCP fixture helper missing.' }
. ([scriptblock]::Create($tcpHelper.Extent.Text))

$privateMarker = 'BF1130_PRIVATE_LEAGUE_NEVER_RENDER'
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('butler-bf1130-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
$validPath = Join-Path $fixtureRoot 'valid.ps1'
[IO.File]::WriteAllText($validPath, '$true | Out-Null', [Text.Encoding]::ASCII)
try {
    foreach ($target in @('/team', '/waivers', '/matchup/autofill', '/league')) {
        # The fixture helper intentionally points core reads at closed
        # loopback port 9; no external provider request is possible.
        $wire = Invoke-Bf1127FirstOpenGet `
            -Target $target -HistoryPath $validPath -TradeHostPath $validPath
        if (-not $wire.StartsWith('HTTP/1.1 500 Internal Server Error', [StringComparison]::Ordinal) -or
            $wire.IndexOf('X-Butler-Failure-Layer: CORE_READ', [StringComparison]::Ordinal) -lt 0 -or
            $wire.IndexOf('Current roster and decision evidence cannot be verified', [StringComparison]::Ordinal) -lt 0 -or
            $wire.IndexOf('No Sleeper transaction was submitted by this page.', [StringComparison]::Ordinal) -lt 0 -or
            $wire.IndexOf('Content-Length:', [StringComparison]::Ordinal) -lt 0 -or
            $wire.IndexOf('Cache-Control: no-store', [StringComparison]::Ordinal) -lt 0 -or
            $wire.IndexOf('href="' + $target + '"', [StringComparison]::Ordinal) -lt 0 -or
            $wire.Length -gt 16000) {
            throw 'BF-1130 BLOCKED: failed local core did not produce bounded, route-safe HTTP 500.'
        }
        foreach ($forbidden in @(
            $privateMarker, $fixtureRoot, 'BF-693 BLOCKED',
            'Exception.Message', 'StackTrace', 'GET ERROR'
        )) {
            if ($wire.IndexOf($forbidden, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                throw 'BF-1130 BLOCKED: failed local core reflected a private or internal error.'
            }
        }
    }
}
finally {
    Remove-Item -LiteralPath $fixtureRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host 'BF-1130 PASS: disconnected local core still yields safe manager HTTP 500 pages.'
