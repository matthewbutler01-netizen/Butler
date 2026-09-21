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
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'
$git = (Get-Command git.exe -ErrorAction Stop).Source
$loopback = [System.Net.IPAddress]::Parse('127.0.0.1')

foreach ($required in @($appLauncher, $powershell, $taskkill, $git)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "BF-843 BLOCKED: required component not found at $required"
    }
}

function Get-WorkingTreeState {
    Push-Location $repoRoot
    try {
        $lines = & $git status --porcelain=v1 --untracked-files=all 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "BF-843 BLOCKED: git status failed with exit code $LASTEXITCODE."
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
        throw 'BF-843 BLOCKED: unable to launch owned Butler process.'
    }
    return $process
}

function Stop-OwnedButler {
    param([AllowNull()]$Process, [int]$Port)
    if ($null -ne $Process) {
        if (-not $Process.HasExited) {
            & $taskkill /PID $Process.Id /T /F 2>$null | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw "BF-843 CLEANUP FAILED: taskkill exited with code $LASTEXITCODE."
            }
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
        Write-Warning ("BF-843 could not remove owned run-state marker: {0}" -f $_.Exception.Message)
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
        throw "BF-843 BLOCKED: $Stage returned HTTP $($Response.StatusCode)."
    }
}

function Get-LineupCard {
    param([string]$Html)

    $options = [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor [System.Text.RegularExpressions.RegexOptions]::Singleline
    $articles = [regex]::Matches($Html, '<article\s+class="manager-decision-card(?:\s+primary)?">(?<body>.*?)</article>', $options)
    foreach ($article in $articles) {
        $body = $article.Groups['body'].Value
        if ($body.IndexOf('<div class="manager-kind">Lineup</div>', [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            continue
        }

        $titleMatch = [regex]::Match($body, '<h3>(?<title>.*?)</h3>', $options)
        $actionMatch = [regex]::Match($body, '<div class="manager-card-actions">\s*<a class="command-button" href="(?<href>[^"]+)">(?<label>.*?)</a>', $options)
        $statusMatch = [regex]::Match($body, '<div class="status [^"]+">(?<status>.*?)</div>\s*$', $options)
        if (-not $titleMatch.Success -or -not $actionMatch.Success -or -not $statusMatch.Success) {
            throw 'BF-843 BLOCKED: lineup Dashboard card is missing title, primary action, or status.'
        }

        return [pscustomobject]@{
            Title = [System.Net.WebUtility]::HtmlDecode($titleMatch.Groups['title'].Value).Trim()
            Href = [System.Net.WebUtility]::HtmlDecode($actionMatch.Groups['href'].Value).Trim()
            Label = [System.Net.WebUtility]::HtmlDecode($actionMatch.Groups['label'].Value).Trim()
            Status = [System.Net.WebUtility]::HtmlDecode($statusMatch.Groups['status'].Value).Trim()
            Body = $body
        }
    }

    throw 'BF-843 BLOCKED: Dashboard did not render a lineup decision card.'
}

function Get-ExpectedLineupRoute {
    param([string]$Title)

    switch ($Title) {
        'Your lineup recommendation is out of date' { return @('/matchup/autofill','Refresh Lineup') }
        'Lineup review needs more evidence' { return @('/matchup','Review Matchup') }
        'Lineup changes are ready to review' { return @('/matchup','Review Matchup') }
        'No lineup change proven' { return @('/matchup','View Matchup') }
        'Your roster needs review first' { return @('/team','Review My Team') }
        'Lineup has not been reviewed yet' { return @('/matchup/autofill','Review Matchup') }
        default { throw "BF-843 BLOCKED: unrecognized live lineup card title: $Title" }
    }
}

$before = Get-WorkingTreeState
if (-not [string]::IsNullOrWhiteSpace($before)) {
    throw "BF-843 BLOCKED: repository must be clean before acceptance. status=$before"
}

$port = Get-FreePort
$root = "http://127.0.0.1:$port"
$process = $null
$failure = $null
$passed = $false

Write-Host 'Butler Dashboard Matchup routing acceptance (BF-843)'
Write-Host "Target: $root"
Write-Host 'Journey: health -> Dashboard -> live lineup card -> state-appropriate manager route.'
Write-Host 'Boundary: GET-only local Butler requests; no refresh; no Butler or Sleeper transaction write.'

try {
    $process = Start-OwnedButler -Port $port
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $healthy = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) {
            throw "BF-843 FAILED: Butler exited during startup with code $($process.ExitCode)."
        }
        try {
            $health = Invoke-Get -Url ($root + '/health') -TimeoutMs 1000
            if ($health.StatusCode -eq 200) {
                $identity = $health.Body | ConvertFrom-Json
                if ($null -ne $identity -and [string]$identity.service -ceq 'butler-app-shell' -and [string]$identity.status -ceq 'ok') {
                    $healthy = $true
                    break
                }
                throw 'BF-843 BLOCKED: selected port is not the expected butler-app-shell.'
            }
        }
        catch [System.Net.WebException] {}
        Start-Sleep -Milliseconds 250
    }
    if (-not $healthy) {
        throw "BF-843 FAILED: Butler did not become healthy within $StartupTimeoutSeconds seconds."
    }
    Write-Host 'Health: BUTLER_APP_SHELL_VERIFIED'

    $dashboard = Invoke-Get -Url ($root + '/') -TimeoutMs ($RequestTimeoutSeconds * 1000)
    Assert-Ok -Response $dashboard -Stage 'Dashboard'

    # BF-871 retired the legacy "Butler Command Center" hero copy. Verify the
    # current decision-first Dashboard structure instead of a presentation string.
    foreach ($marker in @('Your decision queue','id="decision-details"','manager-decision-card','manager-kind')) {
        if ($dashboard.Body.IndexOf($marker, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw "BF-843 BLOCKED: Dashboard is missing current-layout marker: $marker"
        }
    }

    $lineup = Get-LineupCard -Html $dashboard.Body
    $expected = Get-ExpectedLineupRoute -Title $lineup.Title
    $expectedHref = [string]$expected[0]
    $expectedLabel = [string]$expected[1]

    if ($lineup.Href -cne $expectedHref) {
        throw "BF-843 BLOCKED: lineup state '$($lineup.Title)' routed to '$($lineup.Href)' instead of '$expectedHref'."
    }
    if ($lineup.Label -cne $expectedLabel) {
        throw "BF-843 BLOCKED: lineup state '$($lineup.Title)' labeled '$($lineup.Label)' instead of '$expectedLabel'."
    }
    if ($lineup.Body.IndexOf('href="/team">My Team</a>', [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw 'BF-843 BLOCKED: My Team secondary roster-inspection action is missing from the lineup card.'
    }

    Write-Host ("Lineup state: {0} [{1}]" -f $lineup.Title, $lineup.Status)
    Write-Host ("Primary route: {0} ({1})" -f $lineup.Href, $lineup.Label)
    Write-Host 'Dashboard: MATCHUP_ROUTING_VERIFIED'
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
}

$after = $null
try { $after = Get-WorkingTreeState }
catch {
    if ($null -eq $failure) { $failure = $_ } else { Write-Warning $_.Exception.Message }
}
if ($null -eq $failure -and -not [string]::IsNullOrWhiteSpace($after)) {
    $failure = [System.Exception]::new("BF-843 FAILED: repository became dirty during acceptance. status=$after")
}

if ($null -ne $failure) {
    Write-Host 'BF-843 RESULT: FAIL'
    throw $failure
}
if (-not $passed) {
    throw 'BF-843 FAILED: acceptance ended without a result.'
}

Write-Host 'Working tree: CLEAN'
Write-Host 'BF-843 RESULT: COMPLETE'
