param(
    [ValidateRange(1, 600)]
    [int]$StartupTimeoutSeconds = 180,
    [ValidateRange(1, 300)]
    [int]$RequestTimeoutSeconds = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$appLauncher = Join-Path $scriptDir 'butler-app.ps1'
$gradle = Join-Path $repoRoot 'gradlew.bat'
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'
$git = (Get-Command git.exe -ErrorAction Stop).Source
$loopback = [System.Net.IPAddress]::Parse('127.0.0.1')
$localAppData = $env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
}
if ([string]::IsNullOrWhiteSpace($localAppData)) {
    throw 'BF-841 BLOCKED: LocalApplicationData is unavailable.'
}
$configDir = Join-Path $localAppData 'Butler'
$configPath = Join-Path $configDir 'app-league.txt'

foreach ($required in @($appLauncher, $gradle, $powershell, $taskkill, $git)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "BF-841 BLOCKED: required component not found at $required"
    }
}

function Get-WorkingTreeState {
    Push-Location $repoRoot
    try {
        $lines = & $git status --porcelain=v1 --untracked-files=all 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "BF-841 BLOCKED: git status failed with exit code $LASTEXITCODE."
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
        throw 'BF-841 BLOCKED: unable to launch owned Butler process.'
    }
    return $process
}

function Stop-OwnedButler {
    param([AllowNull()]$Process, [int]$Port)
    if ($null -ne $Process) {
        try {
            if (-not $Process.HasExited) {
                & $taskkill /PID $Process.Id /T /F 2>$null | Out-Null
                if ($LASTEXITCODE -ne 0) {
                    throw "BF-841 CLEANUP FAILED: taskkill exited with code $LASTEXITCODE."
                }
            }
        }
        catch {
            throw
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
        Write-Warning ("BF-841 could not remove owned run-state marker: {0}" -f $_.Exception.Message)
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
            StatusText = [string]$response.StatusDescription
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
        $plain = [regex]::Replace([string]$Response.Body, '<[^>]+>', ' ')
        $plain = [System.Net.WebUtility]::HtmlDecode($plain)
        $plain = [regex]::Replace($plain, '\s+', ' ').Trim()
        if ($plain.Length -gt 900) { $plain = $plain.Substring(0, 900) + '...' }
        throw "BF-841 BLOCKED: $Stage returned HTTP $($Response.StatusCode). body=$plain"
    }
}

function Assert-Contains {
    param([string]$Html, [string]$Marker, [string]$Stage)
    if ($Html.IndexOf($Marker, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "BF-841 BLOCKED: $Stage is missing marker: $Marker"
    }
}

function Get-ConfiguredLeagueId {
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
        throw 'BF-841 BLOCKED: Butler app league configuration is missing.'
    }
    $raw = [IO.File]::ReadAllText($configPath, [Text.Encoding]::ASCII).Trim()
    $parsed = [Guid]::Empty
    if ([string]::IsNullOrWhiteSpace($raw) -or -not [Guid]::TryParse($raw, [ref]$parsed)) {
        throw 'BF-841 BLOCKED: Butler app league configuration is invalid.'
    }
    return $parsed.ToString('D').ToLowerInvariant()
}

function Get-ButlerDataDir {
    $configured = [string]$env:BUTLER_APP_DATA_DIR
    if ([string]::IsNullOrWhiteSpace($configured)) {
        $candidate = Join-Path $configDir 'data'
    }
    else {
        if (-not [IO.Path]::IsPathRooted($configured)) {
            throw 'BF-841 BLOCKED: BUTLER_APP_DATA_DIR must be an absolute path.'
        }
        $candidate = $configured
    }
    $resolved = [IO.Path]::GetFullPath($candidate)
    $databasePath = Join-Path $resolved 'butler.db'
    if (-not (Test-Path -LiteralPath $databasePath -PathType Leaf)) {
        throw "BF-841 BLOCKED: governed Butler runtime database is missing at $databasePath"
    }
    return $resolved
}

function Invoke-MatchupPairingSync {
    param(
        [Parameter(Mandatory = $true)][string]$LeagueId,
        [Parameter(Mandatory = $true)][string]$DataDir
    )

    $previousDataDir = [string]$env:BUTLER_APP_DATA_DIR
    $previousPreference = $ErrorActionPreference
    $exitCode = -1
    $lines = @()
    try {
        $env:BUTLER_APP_DATA_DIR = $DataDir
        Push-Location $repoRoot
        try {
            $ErrorActionPreference = 'Continue'
            $lines = @(& $gradle ':bet:bet-cli:sleeperCurrentWeekMatchupSync' "--args=$LeagueId" 2>&1)
            $exitCode = $LASTEXITCODE
        }
        finally {
            Pop-Location
            $ErrorActionPreference = $previousPreference
        }
    }
    finally {
        if ([string]::IsNullOrWhiteSpace($previousDataDir)) {
            Remove-Item Env:BUTLER_APP_DATA_DIR -ErrorAction SilentlyContinue
        }
        else {
            $env:BUTLER_APP_DATA_DIR = $previousDataDir
        }
    }

    $text = ($lines | ForEach-Object { "$_" }) -join [Environment]::NewLine
    if ($exitCode -ne 0) {
        throw "BF-841 BLOCKED: BF-840 matchup pairing sync failed with Gradle exit code $exitCode. output=$text"
    }
    if ($text.IndexOf('BF-840 current weekly matchup pairing synchronized.', [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-841 BLOCKED: BF-840 matchup pairing sync did not report success. output=$text"
    }
    Write-Host 'Pairing sync: BF840_CURRENT_WEEK_EVIDENCE_VERIFIED'
}

$before = Get-WorkingTreeState
if (-not [string]::IsNullOrWhiteSpace($before)) {
    throw "BF-841 BLOCKED: repository must be clean before acceptance. status=$before"
}

$port = Get-FreePort
$root = "http://127.0.0.1:$port"
$process = $null
$failure = $null
$passed = $false

Write-Host 'Butler Weekly Matchup end-to-end acceptance (BF-841)'
Write-Host "Target: $root"
Write-Host 'Journey: BF-840 exact-pairing sync -> health -> exact current pairing -> Lineup Advisor -> opponent roster context.'
Write-Host 'Boundary: one explicit Butler-local matchup evidence sync, then GET-only local Butler requests; /refresh excluded; no Sleeper transaction write.'

$leagueId = Get-ConfiguredLeagueId
$dataDir = Get-ButlerDataDir
Invoke-MatchupPairingSync -LeagueId $leagueId -DataDir $dataDir

try {
    $previousDataDir = [string]$env:BUTLER_APP_DATA_DIR
    $env:BUTLER_APP_DATA_DIR = $dataDir
    $process = Start-OwnedButler -Port $port
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $healthy = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) {
            throw "BF-841 FAILED: Butler exited during startup with code $($process.ExitCode)."
        }
        try {
            $health = Invoke-Get -Url ($root + '/health') -TimeoutMs 1000
            if ($health.StatusCode -eq 200) {
                $identity = $health.Body | ConvertFrom-Json
                if ($null -ne $identity -and [string]$identity.service -ceq 'butler-app-shell' -and [string]$identity.status -ceq 'ok') {
                    $healthy = $true
                    break
                }
                throw 'BF-841 BLOCKED: selected port is not the expected butler-app-shell.'
            }
        }
        catch [System.Net.WebException] {}
        Start-Sleep -Milliseconds 250
    }
    if (-not $healthy) {
        throw "BF-841 FAILED: Butler did not become healthy within $StartupTimeoutSeconds seconds."
    }
    Write-Host 'Health: BUTLER_APP_SHELL_VERIFIED'

    $timeoutMs = $RequestTimeoutSeconds * 1000
    $matchup = Invoke-Get -Url ($root + '/matchup') -TimeoutMs $timeoutMs
    Assert-Ok -Response $matchup -Stage 'Weekly Matchup idle state'

    foreach ($marker in @(
        'Butler - Weekly Matchup',
        'Weekly matchup',
        'PAIRING VERIFIED',
        'Lineup advisor',
        'NOT REVIEWED',
        'href="/matchup/autofill"',
        'Opponent context',
        'Roster profile',
        'href="/matchup"',
        'READ ONLY.'
    )) {
        Assert-Contains -Html $matchup.Body -Marker $marker -Stage 'Weekly Matchup idle state'
    }

    if ($matchup.Body.IndexOf('href="/team/autofill"', [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw 'BF-842 BLOCKED: idle Weekly Matchup escaped to the My Team AutoFill route.'
    }
    Assert-Contains -Html $matchup.Body -Marker '>Review Lineup</a>' -Stage 'Weekly Matchup idle action copy'
    if ($matchup.Body.IndexOf('>Run AutoFill</a>', [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw 'BF-846 BLOCKED: idle Weekly Matchup exposed standalone My Team AutoFill wording.'
    }

    foreach ($blocked in @(
        'Opponent pairing unavailable',
        'Butler Weekly Matchup view blocked',
        'Internal Server Error'
    )) {
        if ($matchup.Body.IndexOf($blocked, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw "BF-841 BLOCKED: Weekly Matchup rendered a fail-closed surface during live acceptance: $blocked"
        }
    }

    foreach ($gambling in @('pick''em','moneyline','sportsbook','betting odds')) {
        if ($matchup.Body.IndexOf($gambling, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw "BF-841 BLOCKED: Weekly Matchup exposed gambling-style language: $gambling"
        }
    }

    if ($matchup.Body.IndexOf('vs.', [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw 'BF-841 BLOCKED: exact user-versus-opponent matchup headline was not rendered.'
    }

    Write-Host 'Matchup: EXACT_PAIRING_RENDERED'
    Write-Host 'Lineup idle: OPT_IN_REVIEW_VERIFIED'

    $review = Invoke-Get -Url ($root + '/matchup/autofill') -TimeoutMs $timeoutMs
    Assert-Ok -Response $review -Stage 'Weekly Matchup explicit lineup review'
    foreach ($marker in @(
        'Butler - Weekly Matchup',
        'PAIRING VERIFIED',
        'Lineup advisor',
        'href="/matchup/autofill"',
        'Opponent context',
        'Roster profile',
        'READ ONLY.'
    )) {
        Assert-Contains -Html $review.Body -Marker $marker -Stage 'Weekly Matchup explicit lineup review'
    }
    if ($review.Body.IndexOf('NOT REVIEWED', [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw 'BF-842 BLOCKED: explicit Matchup AutoFill request remained in NOT REVIEWED state.'
    }
    if ($review.Body.IndexOf('href="/team/autofill"', [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw 'BF-842 BLOCKED: reviewed Weekly Matchup escaped to the My Team AutoFill route.'
    }
    Assert-Contains -Html $review.Body -Marker '>Back to Matchup</a>' -Stage 'Weekly Matchup reviewed action copy'
    if ($review.Body.IndexOf('>Back to My Team</a>', [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw 'BF-846 BLOCKED: reviewed Weekly Matchup displayed a My Team return label for a Matchup destination.'
    }
    foreach ($blocked in @(
        'Opponent pairing unavailable',
        'Butler Weekly Matchup view blocked',
        'Internal Server Error'
    )) {
        if ($review.Body.IndexOf($blocked, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw "BF-842 BLOCKED: explicit Weekly Matchup review rendered a fail-closed surface: $blocked"
        }
    }

    Write-Host 'Lineup review: GOVERNED_LINEUP_ADVISOR_RENDERED'
    Write-Host 'Action copy: MATCHUP_CONTEXT_VERIFIED'
    Write-Host 'Opponent: GOVERNED_OPPONENT_CONTEXT_RENDERED'
    $passed = $true
}
catch {
    $failure = $_
}
finally {
    try { Stop-OwnedButler -Process $process -Port $port }
    catch {
        if ($null -eq $failure) { $failure = $_ } else { Write-Warning $_.Exception.Message }
    }
    if ([string]::IsNullOrWhiteSpace($previousDataDir)) {
        Remove-Item Env:BUTLER_APP_DATA_DIR -ErrorAction SilentlyContinue
    }
    else {
        $env:BUTLER_APP_DATA_DIR = $previousDataDir
    }
}

$after = $null
try { $after = Get-WorkingTreeState }
catch {
    if ($null -eq $failure) { $failure = $_ } else { Write-Warning $_.Exception.Message }
}
if ($null -eq $failure -and -not [string]::IsNullOrWhiteSpace($after)) {
    $failure = [System.Exception]::new("BF-841 FAILED: repository became dirty during acceptance. status=$after")
}

if ($null -ne $failure) {
    Write-Host 'BF-841 RESULT: FAIL'
    throw $failure
}
if (-not $passed) {
    throw 'BF-841 FAILED: acceptance ended without a result.'
}

Write-Host 'Working tree: CLEAN'
Write-Host 'BF-841 RESULT: COMPLETE'
