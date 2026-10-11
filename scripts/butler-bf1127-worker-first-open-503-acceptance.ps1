Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# BF-1127: exercise the real cached public worker on loopback, not a mock
# response generator. Missing or invalid first-open modules must emit an
# explicit safe 503 without contacting the core, Sleeper, or a real league.
$repoRoot = Split-Path -Parent $PSScriptRoot
$cachePath = Join-Path $PSScriptRoot 'butler-app-request-worker-cache.ps1'
if (-not (Test-Path -LiteralPath $cachePath -PathType Leaf)) {
    throw 'BF-1127 BLOCKED: cached public worker is missing.'
}

$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('butler-bf1127-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
$missingPath = Join-Path $fixtureRoot 'absent.ps1'
$malformedPath = Join-Path $fixtureRoot 'invalid.ps1'
$validPath = Join-Path $fixtureRoot 'valid.ps1'
[IO.File]::WriteAllText($malformedPath, 'function BadModule {', [Text.Encoding]::ASCII)
[IO.File]::WriteAllText($validPath, '$true | Out-Null', [Text.Encoding]::ASCII)
$privateMarker = 'BF1127_PRIVATE_LEAGUE_NEVER_RENDER'

function Invoke-Bf1127FirstOpenGet {
    param(
        [Parameter(Mandatory = $true)][string]$Target,
        [Parameter(Mandatory = $true)][string]$HistoryPath,
        [Parameter(Mandatory = $true)][string]$TradeHostPath
    )

    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $caller = $null
    $accepted = $null
    try {
        $listener.Start()
        $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
        $caller = [Net.Sockets.TcpClient]::new()
        $caller.ReceiveTimeout = 5000
        $caller.SendTimeout = 5000
        $caller.Connect('127.0.0.1', $port)
        $accepted = $listener.AcceptTcpClient()
        $requestBytes = [Text.Encoding]::ASCII.GetBytes(
            "GET $Target HTTP/1.1`r`nHost: 127.0.0.1`r`nConnection: close`r`n`r`n")
        $incoming = $caller.GetStream()
        $incoming.Write($requestBytes, 0, $requestBytes.Length)
        $incoming.Flush()

        & $cachePath `
            -Client $accepted `
            -LeagueId $privateMarker `
            -InnerPort 9 `
            -TradeHost $TradeHostPath `
            -TradeLab $validPath `
            -History $HistoryPath `
            -Detail $validPath `
            -DecisionRefresh $validPath `
            -DecisionRefreshRunner $validPath `
            -RepoRoot $repoRoot `
            -RefreshState @{ SyncRoot = [object]::new() }

        # The canonical worker closes this accepted connection on exit;
        # closing again is harmless and bounds the client-side ReadToEnd.
        $accepted.Close()
        $reader = [IO.StreamReader]::new($caller.GetStream(), [Text.Encoding]::UTF8)
        try { return $reader.ReadToEnd() }
        finally { $reader.Dispose() }
    }
    finally {
        if ($null -ne $accepted) { $accepted.Dispose() }
        if ($null -ne $caller) { $caller.Dispose() }
        $listener.Stop()
    }
}

# BF-1128: health is a true pre-module-loading control. Test it before and
# after broken manager GETs so a safe 503 is not mistaken for a dead worker.
function Assert-Bf1128HealthAvailable {
    $wire = Invoke-Bf1127FirstOpenGet `
        -Target '/health' -HistoryPath $missingPath -TradeHostPath $missingPath
    if (-not $wire.StartsWith('HTTP/1.1 200 OK', [StringComparison]::Ordinal) -or
        $wire.IndexOf('"status":"ok"', [StringComparison]::Ordinal) -lt 0 -or
        $wire.IndexOf('"service":"butler-app-shell"', [StringComparison]::Ordinal) -lt 0 -or
        $wire.IndexOf('X-Butler-Failure-Layer:', [StringComparison]::Ordinal) -ge 0 -or
        $wire.IndexOf($privateMarker, [StringComparison]::Ordinal) -ge 0 -or
        $wire.Length -gt 16000) {
        throw 'BF-1128 BLOCKED: worker health path lost independent successful HTTP 200.'
    }
}

try {
    Assert-Bf1128HealthAvailable
    foreach ($case in @(
        @{ Target = '/team'; History = $missingPath; Trade = $validPath },
        @{ Target = '/team'; History = $malformedPath; Trade = $validPath },
        @{ Target = '/matchup/autofill'; History = $missingPath; Trade = $validPath },
        @{ Target = '/trade'; History = $validPath; Trade = $missingPath }
    )) {
        $wire = Invoke-Bf1127FirstOpenGet `
            -Target $case.Target -HistoryPath $case.History -TradeHostPath $case.Trade
        foreach ($required in @(
            'HTTP/1.1 503 Service Unavailable',
            'X-Butler-Failure-Layer: PUBLIC_WORKER',
            'Content-Security-Policy:',
            'Cache-Control: no-store',
            'Content-Length:',
            'Current roster and decision evidence cannot be verified',
            'No Sleeper transaction was submitted by this page.',
            'href="/"'
        )) {
            if ($wire.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
                throw 'BF-1127 BLOCKED: first-open module failure lost a safe HTTP 503 contract.'
            }
        }
        foreach ($forbidden in @(
            $privateMarker, $fixtureRoot, 'BadModule', 'BF-766 BLOCKED',
            'Exception.Message', 'StackTrace', 'GET ERROR'
        )) {
            if ($wire.IndexOf($forbidden, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                throw 'BF-1127 BLOCKED: first-open error reflected private/internal diagnostics.'
            }
        }
        if ($wire.IndexOf('HTTP/1.1 503 Service Unavailable', [StringComparison]::Ordinal) -ne 0 -or
            $wire.Length -gt 16000) {
            throw 'BF-1127 BLOCKED: first-open response is not one bounded 503.'
        }
        Assert-Bf1128HealthAvailable
    }
}
finally {
    Remove-Item -LiteralPath $fixtureRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host 'BF-1128 PASS: real loopback worker health remains available before and after safe module-failure HTTP 503.'
