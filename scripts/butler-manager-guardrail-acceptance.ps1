param(
    [ValidateRange(1, 600)]
    [int]$StartupTimeoutSeconds = 180,

    [ValidateRange(1, 300)]
    [int]$RequestTimeoutSeconds = 120,

    [ValidateRange(1, 32)]
    [int]$Concurrency = 6,

    [ValidateRange(1, 20)]
    [int]$RequestsPerPath = 3
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$appLauncher = Join-Path $scriptDir 'butler-app.ps1'
$baseAcceptance = Join-Path $scriptDir 'butler-acceptance.ps1'
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'
$git = (Get-Command git.exe -ErrorAction Stop).Source
$loopback = [System.Net.IPAddress]::Parse('127.0.0.1')

foreach ($required in @($appLauncher, $baseAcceptance, $powershell, $taskkill, $git)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "BF-844 BLOCKED: required component not found at $required"
    }
}

function Get-WorkingTreeState {
    Push-Location $repoRoot
    try {
        $lines = & $git status --porcelain=v1 --untracked-files=all 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "BF-844 BLOCKED: git status failed with exit code $LASTEXITCODE."
        }
        return (($lines | ForEach-Object { "$_" }) -join [Environment]::NewLine).Trim()
    }
    finally {
        Pop-Location
    }
}

function Get-FreePort {
    $probe = [System.Net.Sockets.TcpListener]::new($loopback, 0)
    try {
        $probe.Start()
        return ([System.Net.IPEndPoint]$probe.LocalEndpoint).Port
    }
    finally {
        try { $probe.Stop() } catch {}
    }
}

function Start-OwnedButler {
    param([int]$Port)

    $quote = [char]34
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $powershell
    $start.Arguments = '-NoLogo -NoProfile -ExecutionPolicy Bypass -File ' + $quote + $appLauncher + $quote + ' -Port ' + $Port + ' -NoBrowser'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::Start($start)
    if ($null -eq $process) {
        throw 'BF-844 BLOCKED: unable to launch owned Butler process.'
    }
    return $process
}

function Stop-OwnedButler {
    param([AllowNull()]$Process, [int]$Port)

    if ($null -ne $Process -and -not $Process.HasExited) {
        & $taskkill /PID $Process.Id /T /F 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "BF-844 CLEANUP FAILED: taskkill exited with code $LASTEXITCODE."
        }
    }

    try {
        $localAppData = $env:LOCALAPPDATA
        if ([string]::IsNullOrWhiteSpace($localAppData)) {
            $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
        }
        if (-not [string]::IsNullOrWhiteSpace($localAppData)) {
            $runState = Join-Path (Join-Path $localAppData 'Butler') ("running-port-{0}.txt" -f $Port)
            if (Test-Path -LiteralPath $runState -PathType Leaf) {
                $raw = [IO.File]::ReadAllText($runState, [Text.Encoding]::ASCII).Trim()
                if ($null -ne $Process -and $raw.StartsWith(([string]$Process.Id + '|'), [System.StringComparison]::Ordinal)) {
                    Remove-Item -LiteralPath $runState -Force
                }
            }
        }
    }
    catch {
        Write-Warning ("BF-844 could not remove owned run-state marker: {0}" -f $_.Exception.Message)
    }
}

function Invoke-Get {
    param([string]$Url, [int]$TimeoutMs)

    $request = [System.Net.HttpWebRequest]::Create($Url)
    $request.Method = 'GET'
    $request.Timeout = $TimeoutMs
    $request.ReadWriteTimeout = $TimeoutMs
    $request.Proxy = $null
    $request.KeepAlive = $false
    $response = $null
    try {
        try {
            $response = $request.GetResponse()
        }
        catch [System.Net.WebException] {
            if ($null -eq $_.Exception.Response) { throw }
            $response = $_.Exception.Response
        }
        $reader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try { $body = $reader.ReadToEnd() } finally { $reader.Dispose() }
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Body = $body
        }
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }
}

function Assert-Ok {
    param($Response, [string]$Stage)
    if ($Response.StatusCode -ne 200) {
        throw "BF-844 BLOCKED: $Stage returned HTTP $($Response.StatusCode)."
    }
}

function Assert-Markers {
    param(
        [string]$Html,
        [string]$Stage,
        [string[]]$Markers
    )

    foreach ($marker in $Markers) {
        if ($Html.IndexOf($marker, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw "BF-844 BLOCKED: $Stage is missing manager guardrail marker: $marker"
        }
    }
}

function Assert-NoBettingPressure {
    param([string]$Html, [string]$Stage)

    foreach ($phrase in @(
        'sportsbook',
        'betting odds',
        'same-game parlay',
        'place a bet',
        'pick''em contest'
    )) {
        if ($Html.IndexOf($phrase, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw "BF-844 BLOCKED: $Stage exposed gambling-style product copy: $phrase"
        }
    }
}

$before = Get-WorkingTreeState
if (-not [string]::IsNullOrWhiteSpace($before)) {
    throw "BF-844 BLOCKED: repository must be clean before acceptance. status=$before"
}

Write-Host 'Butler manager UX and peak-load guardrail acceptance (BF-844)'
Write-Host 'Phase 1: existing Butler acceptance with BF-688 peak-load coverage including /matchup.'
Write-Host 'Boundary: passive GET-only manager reads; /matchup/autofill and /refresh are excluded.'

& $baseAcceptance -StartupTimeoutSeconds $StartupTimeoutSeconds -Concurrency $Concurrency -RequestsPerPath $RequestsPerPath -RequestTimeoutSeconds $RequestTimeoutSeconds
Write-Host 'Peak load: MATCHUP_INCLUDED'

$port = Get-FreePort
$root = "http://127.0.0.1:$port"
$process = $null
$failure = $null

try {
    Write-Host 'Phase 2: manager decision-first and progressive-disclosure guardrails.'
    $process = Start-OwnedButler -Port $port
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $healthy = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) {
            throw "BF-844 FAILED: Butler exited during startup with code $($process.ExitCode)."
        }
        try {
            $health = Invoke-Get -Url ($root + '/health') -TimeoutMs 1000
            if ($health.StatusCode -eq 200) {
                $identity = $health.Body | ConvertFrom-Json
                if ($null -ne $identity -and [string]$identity.service -ceq 'butler-app-shell' -and [string]$identity.status -ceq 'ok') {
                    $healthy = $true
                    break
                }
                throw 'BF-844 BLOCKED: selected port is not the expected butler-app-shell.'
            }
        }
        catch [System.Net.WebException] {}
        Start-Sleep -Milliseconds 250
    }
    if (-not $healthy) {
        throw "BF-844 FAILED: Butler did not become healthy within $StartupTimeoutSeconds seconds."
    }
    Write-Host 'Health: BUTLER_APP_SHELL_VERIFIED'

    $timeoutMs = $RequestTimeoutSeconds * 1000

    $dashboard = Invoke-Get -Url ($root + '/') -TimeoutMs $timeoutMs
    Assert-Ok -Response $dashboard -Stage 'Dashboard'
    Assert-Markers -Html $dashboard.Body -Stage 'Dashboard' -Markers @(
        'Butler Command Center',
        'What matters now',
        'Your decision queue',
        'View decision details',
        '<details'
    )
    Assert-NoBettingPressure -Html $dashboard.Body -Stage 'Dashboard'
    Write-Host 'Dashboard: DECISION_FIRST_AND_DISCLOSURE_VERIFIED'

    $matchup = Invoke-Get -Url ($root + '/matchup') -TimeoutMs $timeoutMs
    Assert-Ok -Response $matchup -Stage 'Weekly Matchup'
    Assert-Markers -Html $matchup.Body -Stage 'Weekly Matchup' -Markers @(
        'Weekly matchup',
        'Lineup advisor',
        'READ ONLY.'
    )
    $verifiedPairing = $matchup.Body.IndexOf('PAIRING VERIFIED', [System.StringComparison]::OrdinalIgnoreCase) -ge 0
    $unavailablePairing = $matchup.Body.IndexOf('Opponent pairing unavailable', [System.StringComparison]::OrdinalIgnoreCase) -ge 0
    if (-not $verifiedPairing -and -not $unavailablePairing) {
        throw 'BF-844 BLOCKED: Weekly Matchup exposed neither verified pairing nor the governed fail-closed pairing state.'
    }
    if ($verifiedPairing) {
        Assert-Markers -Html $matchup.Body -Stage 'Weekly Matchup verified pairing' -Markers @('Pairing evidence','<details')
    }
    else {
        Assert-Markers -Html $matchup.Body -Stage 'Weekly Matchup fail-closed pairing' -Markers @('EVIDENCE NEEDED')
    }
    Assert-NoBettingPressure -Html $matchup.Body -Stage 'Weekly Matchup'
    Write-Host 'Matchup: DECISION_FIRST_AND_EVIDENCE_BOUNDARY_VERIFIED'

    $waivers = Invoke-Get -Url ($root + '/waivers') -TimeoutMs $timeoutMs
    Assert-Ok -Response $waivers -Stage 'Waiver Board'
    Assert-Markers -Html $waivers.Body -Stage 'Waiver Board' -Markers @(
        'Butler waiver decision',
        'What to do now',
        'Technical and audit details',
        '<details',
        'READ ONLY'
    )
    Assert-NoBettingPressure -Html $waivers.Body -Stage 'Waiver Board'
    Write-Host 'Waivers: DECISION_FIRST_AND_DISCLOSURE_VERIFIED'

    $trade = Invoke-Get -Url ($root + '/trade?load=1') -TimeoutMs $timeoutMs
    Assert-Ok -Response $trade -Stage 'Trade Analyzer'
    Assert-Markers -Html $trade.Body -Stage 'Trade Analyzer' -Markers @(
        'Analyze a trade',
        'Choose a league opponent',
        'READ ONLY'
    )
    Assert-NoBettingPressure -Html $trade.Body -Stage 'Trade Analyzer'
    Write-Host 'Trade: STAGED_DECISION_FLOW_VERIFIED'

    Write-Host 'Gambling pressure: ABSENT_FROM_CHECKED_MANAGER_COPY'
}
catch {
    $failure = $_
}
finally {
    try { Stop-OwnedButler -Process $process -Port $port }
    catch {
        if ($null -eq $failure) { $failure = $_ } else { Write-Warning $_.Exception.Message }
    }
}

$after = $null
try { $after = Get-WorkingTreeState }
catch {
    if ($null -eq $failure) { $failure = $_ } else { Write-Warning $_.Exception.Message }
}
if ($null -eq $failure -and -not [string]::IsNullOrWhiteSpace($after)) {
    $failure = [System.Exception]::new("BF-844 FAILED: repository became dirty during acceptance. status=$after")
}

if ($null -ne $failure) {
    Write-Host 'BF-844 RESULT: FAIL'
    throw $failure
}

Write-Host 'Working tree: CLEAN'
Write-Host 'BF-844 RESULT: COMPLETE'
