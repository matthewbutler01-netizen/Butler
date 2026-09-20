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
        throw "BF-885 BLOCKED: required component not found at $required"
    }
}

function Get-WorkingTreeState {
    Push-Location $repoRoot
    try {
        $lines = & $git status --porcelain=v1 --untracked-files=all 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "BF-885 BLOCKED: git status failed with exit code $LASTEXITCODE."
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
    param([Parameter(Mandatory = $true)][int]$Port)

    $quote = [char]34
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $powershell
    $start.Arguments = '-NoLogo -NoProfile -ExecutionPolicy Bypass -File ' + $quote + $appLauncher + $quote + ' -Port ' + $Port + ' -NoBrowser'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (-not $process.Start()) {
        throw 'BF-885 BLOCKED: unable to launch owned Butler process.'
    }

    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process | Add-Member -NotePropertyName ButlerStdoutTask -NotePropertyValue $stdoutTask
    $process | Add-Member -NotePropertyName ButlerStderrTask -NotePropertyValue $stderrTask
    return $process
}

function Get-BoundedStartupOutput {
    param([Parameter(Mandatory = $true)]$Process)

    try { [void]$Process.WaitForExit(2000) } catch {}

    $parts = New-Object System.Collections.Generic.List[string]
    foreach ($entry in @(
        [pscustomobject]@{ Label = 'stdout'; Property = 'ButlerStdoutTask' },
        [pscustomobject]@{ Label = 'stderr'; Property = 'ButlerStderrTask' }
    )) {
        try {
            $property = $Process.PSObject.Properties[$entry.Property]
            if ($null -eq $property -or $null -eq $property.Value) { continue }
            $task = $property.Value
            if (-not $task.IsCompleted) { continue }
            $text = [string]$task.Result
            if ([string]::IsNullOrWhiteSpace($text)) { continue }
            $text = [regex]::Replace($text, '\s+', ' ').Trim()
            if ($text.Length -gt 900) {
                $text = '...' + $text.Substring($text.Length - 900)
            }
            $parts.Add(($entry.Label + '=' + $text))
        }
        catch {
        }
    }
    return ($parts -join '; ')
}

function Stop-OwnedButler {
    param([AllowNull()]$Process, [int]$Port)

    if ($null -ne $Process -and -not $Process.HasExited) {
        & $taskkill /PID $Process.Id /T /F 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "BF-885 CLEANUP FAILED: taskkill exited with code $LASTEXITCODE."
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
        Write-Warning ("BF-885 could not remove owned run-state marker: {0}" -f $_.Exception.Message)
    }
}

function Invoke-Get {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][int]$TimeoutMs
    )

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
            Body = [string]$body
        }
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }
}

function Get-ManagerRecoveryTechnicalDetail {
    param([Parameter(Mandatory = $true)][string]$Html)

    $match = [regex]::Match(
        $Html,
        '<details><summary>Technical details</summary><div class="technical">(?<detail>.*?)</div></details>',
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
            [System.Text.RegularExpressions.RegexOptions]::Singleline
    )
    if (-not $match.Success) { return $null }

    $detail = [regex]::Replace($match.Groups['detail'].Value, '<[^>]+>', ' ')
    $detail = [System.Net.WebUtility]::HtmlDecode($detail)
    $detail = [regex]::Replace($detail, '\s+', ' ').Trim()
    if ([string]::IsNullOrWhiteSpace($detail)) { return $null }
    if ($detail.Length -gt 1200) { $detail = $detail.Substring(0, 1200) + '...' }
    return $detail
}

function Assert-Status {
    param(
        [Parameter(Mandatory = $true)]$Response,
        [Parameter(Mandatory = $true)][int]$Expected,
        [Parameter(Mandatory = $true)][string]$Stage
    )

    if ($Response.StatusCode -ne $Expected) {
        $detail = Get-ManagerRecoveryTechnicalDetail -Html ([string]$Response.Body)
        if (-not [string]::IsNullOrWhiteSpace([string]$detail)) {
            throw "BF-885 FAILED: $Stage returned HTTP $($Response.StatusCode), expected $Expected. technical=$detail"
        }

        $plain = [regex]::Replace([string]$Response.Body, '<[^>]+>', ' ')
        $plain = [System.Net.WebUtility]::HtmlDecode($plain)
        $plain = [regex]::Replace($plain, '\s+', ' ').Trim()
        if ($plain.Length -gt 700) { $plain = $plain.Substring(0, 700) + '...' }
        throw "BF-885 FAILED: $Stage returned HTTP $($Response.StatusCode), expected $Expected. body=$plain"
    }
}

function Assert-Markers {
    param(
        [Parameter(Mandatory = $true)][string]$Html,
        [Parameter(Mandatory = $true)][string]$Stage,
        [Parameter(Mandatory = $true)][string[]]$Markers
    )

    foreach ($marker in $Markers) {
        if ($Html.IndexOf($marker, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw "BF-885 FAILED: $Stage is missing product marker: $marker"
        }
    }
}

function Assert-NoRawDeveloperFailure {
    param(
        [Parameter(Mandatory = $true)][string]$Html,
        [Parameter(Mandatory = $true)][string]$Stage
    )

    foreach ($marker in @(
        '<h1>Butler app blocked</h1>',
        '<h1>Butler League view blocked</h1>',
        '<h1>Butler My Team view blocked</h1>',
        '<h1>Butler Player Detail blocked</h1>',
        '<h1>Butler Franchise Detail blocked</h1>',
        '<h1>Butler Player Search blocked</h1>',
        '<h1>Butler Weekly Matchup view blocked</h1>'
    )) {
        if ($Html.IndexOf($marker, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw "BF-885 FAILED: $Stage exposed raw developer failure UI: $marker"
        }
    }
}

function Get-FirstSafeHref {
    param(
        [Parameter(Mandatory = $true)][string]$Html,
        [Parameter(Mandatory = $true)][string]$Pattern
    )

    $match = [regex]::Match($Html, $Pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (-not $match.Success) { return $null }

    $href = [System.Net.WebUtility]::HtmlDecode($match.Groups['href'].Value)
    if ([string]::IsNullOrWhiteSpace($href) -or -not $href.StartsWith('/', [System.StringComparison]::Ordinal)) {
        throw 'BF-885 BLOCKED: discovered detail link is not an internal Butler path.'
    }
    if ($href.Contains('://') -or $href.StartsWith('//', [System.StringComparison]::Ordinal)) {
        throw 'BF-885 BLOCKED: discovered detail link escaped the Butler origin.'
    }
    return $href
}

function Write-Pass {
    param([Parameter(Mandatory = $true)][string]$Label)
    Write-Host ("{0}: PASS" -f $Label)
}

function Write-Skip {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$Reason
    )
    Write-Host ("{0}: SKIP ({1})" -f $Label, $Reason)
}

$before = Get-WorkingTreeState
if (-not [string]::IsNullOrWhiteSpace($before)) {
    throw "BF-885 BLOCKED: repository must be clean before acceptance. status=$before"
}

$port = Get-FreePort
$root = "http://127.0.0.1:$port"
$timeoutMs = $RequestTimeoutSeconds * 1000
$process = $null
$failure = $null
$passed = $false

Write-Host 'Butler end-to-end manager journey acceptance (BF-885)'
Write-Host "Target: $root"
Write-Host 'Boundary: GET-only local Butler journey; no /refresh, no POST, no lineup/waiver/trade execution, no Sleeper write.'

try {
    $process = Start-OwnedButler -Port $port
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $healthy = $false

    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) {
            $diagnostic = Get-BoundedStartupOutput -Process $process
            if ([string]::IsNullOrWhiteSpace($diagnostic)) {
                throw "BF-885 FAILED: Butler exited during startup with code $($process.ExitCode)."
            }
            throw "BF-885 FAILED: Butler exited during startup with code $($process.ExitCode); startup=$diagnostic"
        }

        try {
            $health = Invoke-Get -Url ($root + '/health') -TimeoutMs 1000
            if ($health.StatusCode -eq 200) {
                $identity = $health.Body | ConvertFrom-Json
                if ($null -ne $identity -and [string]$identity.service -ceq 'butler-app-shell' -and [string]$identity.status -ceq 'ok') {
                    $healthy = $true
                    break
                }
                throw 'BF-885 BLOCKED: selected port is not the expected butler-app-shell.'
            }
        }
        catch [System.Net.WebException] {}

        Start-Sleep -Milliseconds 250
    }

    if (-not $healthy) {
        throw "BF-885 FAILED: Butler did not become healthy within $StartupTimeoutSeconds seconds."
    }
    Write-Pass -Label 'Health before journey'

    $dashboard = Invoke-Get -Url ($root + '/') -TimeoutMs $timeoutMs
    Assert-Status -Response $dashboard -Expected 200 -Stage 'Dashboard'
    Assert-Markers -Html $dashboard.Body -Stage 'Dashboard' -Markers @('Priority 01','Your decision queue')
    Assert-NoRawDeveloperFailure -Html $dashboard.Body -Stage 'Dashboard'
    Write-Pass -Label 'Dashboard'

    $team = Invoke-Get -Url ($root + '/team') -TimeoutMs $timeoutMs
    Assert-Status -Response $team -Expected 200 -Stage 'My Team'
    Assert-Markers -Html $team.Body -Stage 'My Team' -Markers @('How Butler reads this roster','Review Matchup','Find a player')
    Assert-NoRawDeveloperFailure -Html $team.Body -Stage 'My Team'
    Write-Pass -Label 'My Team'

    $matchup = Invoke-Get -Url ($root + '/matchup') -TimeoutMs $timeoutMs
    Assert-Status -Response $matchup -Expected 200 -Stage 'Matchup'
    Assert-Markers -Html $matchup.Body -Stage 'Matchup' -Markers @('Weekly matchup','Lineup advisor','READ ONLY')
    Assert-NoRawDeveloperFailure -Html $matchup.Body -Stage 'Matchup'
    Write-Pass -Label 'Matchup'

    $playerSearch = Invoke-Get -Url ($root + '/players') -TimeoutMs $timeoutMs
    Assert-Status -Response $playerSearch -Expected 200 -Stage 'Player Search'
    Assert-Markers -Html $playerSearch.Body -Stage 'Player Search' -Markers @('Find a rostered player','Back to My Team','Back to League')
    Assert-NoRawDeveloperFailure -Html $playerSearch.Body -Stage 'Player Search'
    Write-Pass -Label 'Player Search'

    $playerHref = Get-FirstSafeHref -Html $team.Body -Pattern 'href="(?<href>/player\?id=[^"]+)"'
    if ([string]::IsNullOrWhiteSpace([string]$playerHref)) {
        Write-Skip -Label 'Player Detail' -Reason 'no exact canonical Player Detail link rendered in current My Team evidence'
    }
    else {
        $player = Invoke-Get -Url ($root + $playerHref) -TimeoutMs $timeoutMs
        Assert-Status -Response $player -Expected 200 -Stage 'Player Detail'
        Assert-Markers -Html $player.Body -Stage 'Player Detail' -Markers @('Player Detail','Back to My Team','Player Search','READ ONLY')
        Assert-NoRawDeveloperFailure -Html $player.Body -Stage 'Player Detail'
        Write-Pass -Label 'Player Detail'
    }

    $league = Invoke-Get -Url ($root + '/league') -TimeoutMs $timeoutMs
    Assert-Status -Response $league -Expected 200 -Stage 'League'
    Assert-Markers -Html $league.Body -Stage 'League' -Markers @('League status','What deserves attention','Find a player')
    Assert-NoRawDeveloperFailure -Html $league.Body -Stage 'League'
    Write-Pass -Label 'League'

    $franchiseHref = Get-FirstSafeHref -Html $league.Body -Pattern 'href="(?<href>/franchise\?id=[^"]+)"'
    if ([string]::IsNullOrWhiteSpace([string]$franchiseHref)) {
        Write-Skip -Label 'Franchise Detail' -Reason 'no exact Franchise Detail link rendered in current League evidence'
    }
    else {
        $franchise = Invoke-Get -Url ($root + $franchiseHref) -TimeoutMs $timeoutMs
        Assert-Status -Response $franchise -Expected 200 -Stage 'Franchise Detail'
        Assert-Markers -Html $franchise.Body -Stage 'Franchise Detail' -Markers @('Franchise Detail','Coverage and missingness','Back to League','READ ONLY')
        Assert-NoRawDeveloperFailure -Html $franchise.Body -Stage 'Franchise Detail'
        Write-Pass -Label 'Franchise Detail'
    }

    $waivers = Invoke-Get -Url ($root + '/waivers') -TimeoutMs $timeoutMs
    Assert-Status -Response $waivers -Expected 200 -Stage 'Waiver Board'
    Assert-Markers -Html $waivers.Body -Stage 'Waiver Board' -Markers @('Butler waiver decision','Next step','Decision details','READ ONLY')
    Assert-NoRawDeveloperFailure -Html $waivers.Body -Stage 'Waiver Board'
    Write-Pass -Label 'Waiver Board'

    $trade = Invoke-Get -Url ($root + '/trade?load=1') -TimeoutMs $timeoutMs
    Assert-Status -Response $trade -Expected 200 -Stage 'Trade Analyzer'
    Assert-Markers -Html $trade.Body -Stage 'Trade Analyzer' -Markers @('Analyze a trade','Choose a league opponent','READ ONLY')
    Assert-NoRawDeveloperFailure -Html $trade.Body -Stage 'Trade Analyzer'
    Write-Pass -Label 'Trade Analyzer'

    $history = Invoke-Get -Url ($root + '/history?load=1') -TimeoutMs $timeoutMs
    Assert-Status -Response $history -Expected 200 -Stage 'Decision History'
    Assert-Markers -Html $history.Body -Stage 'Decision History' -Markers @('Recorded waiver decisions','Decision History reads recorded governed waiver history only')
    Assert-NoRawDeveloperFailure -Html $history.Body -Stage 'Decision History'
    Write-Pass -Label 'Decision History'

    $notFound = Invoke-Get -Url ($root + '/__bf885_not_found__') -TimeoutMs $timeoutMs
    Assert-Status -Response $notFound -Expected 404 -Stage 'Manager not-found recovery'
    Assert-Markers -Html $notFound.Body -Stage 'Manager not-found recovery' -Markers @('Page not found','SAFE RECOVERY.','Dashboard','My Team','League')
    Write-Pass -Label 'Manager not-found recovery'

    $healthAfter = Invoke-Get -Url ($root + '/health') -TimeoutMs 1000
    Assert-Status -Response $healthAfter -Expected 200 -Stage 'Health after journey'
    $identityAfter = $healthAfter.Body | ConvertFrom-Json
    if ($null -eq $identityAfter -or [string]$identityAfter.service -cne 'butler-app-shell' -or [string]$identityAfter.status -cne 'ok') {
        throw 'BF-885 FAILED: health after journey did not identify the expected butler-app-shell.'
    }
    Write-Pass -Label 'Health after journey'

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
    $failure = [System.Exception]::new("BF-885 FAILED: repository became dirty during acceptance. status=$after")
}

if ($null -ne $failure) {
    Write-Host 'BF-885 RESULT: FAIL'
    throw $failure
}
if (-not $passed) {
    throw 'BF-885 FAILED: acceptance ended without a result.'
}

Write-Host 'Working tree: CLEAN'
Write-Host 'BF-885 RESULT: COMPLETE'
