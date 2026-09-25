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

    # BF-912: later visual/mobile transforms are allowed to add attributes or
    # whitespace around native disclosure markup. Match the semantic recovery
    # structure rather than one byte-for-byte HTML shape.
    $match = [regex]::Match(
        $Html,
        '(?is)<details\b[^>]*>\s*<summary\b[^>]*>\s*Technical details\s*</summary>\s*<div\b[^>]*class="[^"]*\btechnical\b[^"]*"[^>]*>(?<detail>.*?)</div>\s*</details>'
    )
    if (-not $match.Success) {
        # Last-resort manager-recovery fallback: if the technical label is
        # present but markup has changed again, return a bounded plain-text tail
        # beginning at that label so live failures cannot lose their cause.
        $plainHtml = [regex]::Replace(
            $Html,
            '(?is)<style\b[^>]*>.*?</style>|<script\b[^>]*>.*?</script>',
            ' '
        )
        $plain = [regex]::Replace($plainHtml, '<[^>]+>', ' ')
        $plain = [System.Net.WebUtility]::HtmlDecode($plain)
        $plain = [regex]::Replace($plain, '\s+', ' ').Trim()
        $marker = $plain.IndexOf('Technical details', [System.StringComparison]::OrdinalIgnoreCase)
        if ($marker -lt 0) { return $null }
        $detail = $plain.Substring($marker + 'Technical details'.Length).Trim()
        if ([string]::IsNullOrWhiteSpace($detail)) { return $null }
        if ($detail.Length -gt 1600) { $detail = $detail.Substring(0, 1600) + '...' }
        return $detail
    }

    $detail = [regex]::Replace($match.Groups['detail'].Value, '<[^>]+>', ' ')
    $detail = [System.Net.WebUtility]::HtmlDecode($detail)
    $detail = [regex]::Replace($detail, '\s+', ' ').Trim()
    if ([string]::IsNullOrWhiteSpace($detail)) { return $null }
    if ($detail.Length -gt 1600) { $detail = $detail.Substring(0, 1600) + '...' }
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

        $plainHtml = [regex]::Replace(
            [string]$Response.Body,
            '(?is)<style\b[^>]*>.*?</style>|<script\b[^>]*>.*?</script>',
            ' '
        )
        $plain = [regex]::Replace($plainHtml, '<[^>]+>', ' ')
        $plain = [System.Net.WebUtility]::HtmlDecode($plain)
        $plain = [regex]::Replace($plain, '\s+', ' ').Trim()
        if ($plain.Length -gt 1600) { $plain = '...' + $plain.Substring($plain.Length - 1600) }
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

function Assert-AbsentMarkers {
    param(
        [Parameter(Mandatory = $true)][string]$Html,
        [Parameter(Mandatory = $true)][string]$Stage,
        [Parameter(Mandatory = $true)][string[]]$Markers
    )

    foreach ($marker in $Markers) {
        if ($Html.IndexOf($marker, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw "BF-885 FAILED: $Stage exposed hidden technical marker: $marker"
        }
    }
}

function Get-ManagerFirstScanHtml {
    param([Parameter(Mandatory = $true)][string]$Html)

    # BF-912: native details/disclosure may retain exact audit and technical proof.
    # The first-scan contract intentionally evaluates only content visible before
    # the manager opens those disclosures.
    return [regex]::Replace(
        $Html,
        '(?is)<details\b[^>]*>.*?</details>',
        ' '
    )
}

function Assert-PrimaryNavigation {
    param(
        [Parameter(Mandatory = $true)][string]$Html,
        [Parameter(Mandatory = $true)][string]$Stage
    )

    foreach ($marker in @(
        'href="/">Dashboard</a>',
        'href="/team">My Team</a>',
        'href="/matchup">Matchup</a>',
        'href="/waivers">Waiver Board</a>',
        'href="/league">League</a>',
        'href="/trade">Trade Analyzer</a>',
        'href="/history">History</a>'
    )) {
        if ($Html.IndexOf($marker, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw "BF-912 FAILED: $Stage is missing primary navigation marker: $marker"
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

function Invoke-MyTeamDirectDiagnostic {
    $localAppData = $env:LOCALAPPDATA
    if ([string]::IsNullOrWhiteSpace($localAppData)) {
        $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    }
    if ([string]::IsNullOrWhiteSpace($localAppData)) {
        return 'direct-team-bundle=BLOCKED LocalApplicationData unavailable'
    }

    $configDir = Join-Path $localAppData 'Butler'
    $leaguePath = Join-Path $configDir 'app-league.txt'
    if (-not (Test-Path -LiteralPath $leaguePath -PathType Leaf)) {
        return 'direct-team-bundle=BLOCKED configured league id unavailable'
    }
    $leagueId = [IO.File]::ReadAllText($leaguePath, [Text.Encoding]::ASCII).Trim()
    if ([string]::IsNullOrWhiteSpace($leagueId)) {
        return 'direct-team-bundle=BLOCKED configured league id empty'
    }

    $dataDir = [string]$env:BUTLER_APP_DATA_DIR
    if ([string]::IsNullOrWhiteSpace($dataDir)) { $dataDir = Join-Path $configDir 'data' }
    $runtimeLib = Join-Path $repoRoot 'bet\bet-cli\build\install\bet-cli\lib'
    if (-not (Test-Path -LiteralPath $dataDir -PathType Container)) {
        return 'direct-team-bundle=BLOCKED governed runtime data directory unavailable'
    }
    if (-not (Test-Path -LiteralPath $runtimeLib -PathType Container)) {
        return 'direct-team-bundle=BLOCKED prepared Butler Java runtime unavailable'
    }

    try { $java = (Get-Command java.exe -ErrorAction Stop).Source }
    catch { return 'direct-team-bundle=BLOCKED java.exe unavailable' }

    $classPath = Join-Path $runtimeLib '*'
    $previousPreference = $ErrorActionPreference
    $lines = $null
    $exitCode = $null
    Push-Location $dataDir
    try {
        try {
            $ErrorActionPreference = 'Continue'
            $lines = & $java '--enable-native-access=ALL-UNNAMED' '-cp' $classPath 'io.butler.bet.cli.ButlerMyTeamEvidenceBundleCli' $leagueId 2>&1
            $exitCode = $LASTEXITCODE
        }
        finally { $ErrorActionPreference = $previousPreference }
    }
    finally { Pop-Location }

    $text = (($lines | ForEach-Object { "$_" }) -join ' | ')
    $text = [regex]::Replace($text, '\s+', ' ').Trim()
    if ($text.Length -gt 3200) { $text = '...' + $text.Substring($text.Length - 3200) }
    return ("direct-team-bundle exit={0}; output={1}" -f $exitCode, $text)
}

function Invoke-PlayerDetailDirectDiagnostic {
    param([Parameter(Mandatory = $true)][string]$PlayerHref)

    $match = [regex]::Match($PlayerHref, '(?:\?|&)id=(?<id>[^&]+)', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (-not $match.Success) { return 'direct-cli=BLOCKED player id missing from canonical Player Detail href' }
    $playerId = [System.Uri]::UnescapeDataString($match.Groups['id'].Value)

    $localAppData = $env:LOCALAPPDATA
    if ([string]::IsNullOrWhiteSpace($localAppData)) { $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData) }
    if ([string]::IsNullOrWhiteSpace($localAppData)) { return 'direct-cli=BLOCKED LocalApplicationData unavailable' }

    $configDir = Join-Path $localAppData 'Butler'
    $leaguePath = Join-Path $configDir 'app-league.txt'
    if (-not (Test-Path -LiteralPath $leaguePath -PathType Leaf)) { return 'direct-cli=BLOCKED configured league id unavailable' }
    $leagueId = [IO.File]::ReadAllText($leaguePath, [Text.Encoding]::ASCII).Trim()
    if ([string]::IsNullOrWhiteSpace($leagueId)) { return 'direct-cli=BLOCKED configured league id empty' }

    $dataDir = [string]$env:BUTLER_APP_DATA_DIR
    if ([string]::IsNullOrWhiteSpace($dataDir)) { $dataDir = Join-Path $configDir 'data' }
    $runtimeLib = Join-Path $repoRoot 'bet\bet-cli\build\install\bet-cli\lib'
    if (-not (Test-Path -LiteralPath $dataDir -PathType Container)) { return 'direct-cli=BLOCKED governed runtime data directory unavailable' }
    if (-not (Test-Path -LiteralPath $runtimeLib -PathType Container)) { return 'direct-cli=BLOCKED prepared Butler Java runtime unavailable' }

    try { $java = (Get-Command java.exe -ErrorAction Stop).Source }
    catch { return 'direct-cli=BLOCKED java.exe unavailable' }

    $classPath = Join-Path $runtimeLib '*'
    $previousPreference = $ErrorActionPreference
    $lines = $null
    $exitCode = $null
    Push-Location $dataDir
    try {
        try {
            $ErrorActionPreference = 'Continue'
            $lines = & $java '--enable-native-access=ALL-UNNAMED' '-cp' $classPath 'io.butler.bet.cli.ButlerCommandRouter' 'league' 'player-detail' $leagueId $playerId 2>&1
            $exitCode = $LASTEXITCODE
        }
        finally { $ErrorActionPreference = $previousPreference }
    }
    finally { Pop-Location }

    $text = (($lines | ForEach-Object { "$_" }) -join ' | ')
    $text = [regex]::Replace($text, '\s+', ' ').Trim()
    if ($text.Length -gt 2400) { $text = '...' + $text.Substring($text.Length - 2400) }
    return ("direct-cli exit={0}; player={1}; output={2}" -f $exitCode, $playerId, $text)
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
Write-Host 'BF-912 contract: current all-seven-page manager-first stabilization'
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
    Assert-Markers -Html $dashboard.Body -Stage 'Dashboard' -Markers @('Priority 01','Week at a glance','Your fantasy week in one view','After Priority 01','Other priorities')
    Assert-PrimaryNavigation -Html $dashboard.Body -Stage 'Dashboard'
    Assert-NoRawDeveloperFailure -Html $dashboard.Body -Stage 'Dashboard'
    Write-Pass -Label 'Dashboard'

    $team = Invoke-Get -Url ($root + '/team') -TimeoutMs $timeoutMs
    if ($team.StatusCode -ne 200) {
        $teamTechnical = Get-ManagerRecoveryTechnicalDetail -Html ([string]$team.Body)
        $teamDirect = Invoke-MyTeamDirectDiagnostic
        $teamTechnicalText = if ([string]::IsNullOrWhiteSpace([string]$teamTechnical)) {
            $teamPlainHtml = [regex]::Replace(
                [string]$team.Body,
                '(?is)<style\b[^>]*>.*?</style>|<script\b[^>]*>.*?</script>',
                ' '
            )
            $teamPlain = [regex]::Replace($teamPlainHtml, '<[^>]+>', ' ')
            $teamPlain = [System.Net.WebUtility]::HtmlDecode($teamPlain)
            $teamPlain = [regex]::Replace($teamPlain, '\s+', ' ').Trim()
            if ($teamPlain.Length -gt 2400) {
                $teamPlain = $teamPlain.Substring(0, 2400) + '...'
            }
            if ([string]::IsNullOrWhiteSpace($teamPlain)) {
                'recovery-technical=unavailable; recovery-body=unavailable'
            }
            else {
                'recovery-technical=unavailable; recovery-body=' + $teamPlain
            }
        }
        else {
            'recovery-technical=' + $teamTechnical
        }

        # BF-912 live-data failures can happen inside the preserved staged core.
        # Stop only the acceptance-owned Butler tree so redirected stdout/stderr
        # complete, then include the exact BF-884/BF-908 warning in the failure.
        try { Stop-OwnedButler -Process $process -Port $port } catch {}
        $teamProcessOutput = Get-BoundedStartupOutput -Process $process
        if ([string]::IsNullOrWhiteSpace([string]$teamProcessOutput)) {
            $teamProcessOutput = 'process-output=unavailable'
        }
        else {
            $teamProcessOutput = 'process-output=' + $teamProcessOutput
        }

        throw "BF-912 FAILED: My Team returned HTTP $($team.StatusCode), expected 200. $teamTechnicalText; $teamDirect; $teamProcessOutput"
    }
    Assert-Markers -Html $team.Body -Stage 'My Team' -Markers @('Roster hub','Lineup and depth at a glance','Player Search','Player Compare','Roster construction','<h2>Draft capital</h2>')
    Assert-PrimaryNavigation -Html $team.Body -Stage 'My Team'
    Assert-NoRawDeveloperFailure -Html $team.Body -Stage 'My Team'
    Write-Pass -Label 'My Team'

    $matchup = Invoke-Get -Url ($root + '/matchup') -TimeoutMs $timeoutMs
    Assert-Status -Response $matchup -Expected 200 -Stage 'Matchup'
    Assert-Markers -Html $matchup.Body -Stage 'Matchup' -Markers @('Weekly matchup','What to do now','READ ONLY')
    Assert-Markers -Html $matchup.Body -Stage 'Matchup BF-926 actions' -Markers @('Open My Team')
    Assert-PrimaryNavigation -Html $matchup.Body -Stage 'Matchup'
    Assert-NoRawDeveloperFailure -Html $matchup.Body -Stage 'Matchup'

    $matchupOpponentUnavailable = $matchup.Body.IndexOf('Opponent data is incomplete', [System.StringComparison]::Ordinal) -ge 0
    if (-not $matchupOpponentUnavailable) {
        Assert-Markers -Html $matchup.Body -Stage 'Matchup opponent actions' -Markers @('Scout opponent','Trade with opponent')

        $matchupScoutHref = Get-FirstSafeHref -Html $matchup.Body -Pattern 'href="(?<href>/franchise\?id=[^"]+)">Scout opponent</a>'
        $matchupTradeHref = Get-FirstSafeHref -Html $matchup.Body -Pattern 'href="(?<href>/trade\?opponent=[^"]+)">Trade with opponent</a>'
        if ([string]::IsNullOrWhiteSpace([string]$matchupScoutHref) -or [string]::IsNullOrWhiteSpace([string]$matchupTradeHref)) {
            throw 'BF-926 FAILED: confirmed Matchup did not expose exact opponent Scout + Trade actions.'
        }

        $scoutIdMatch = [regex]::Match($matchupScoutHref, '(?:\?|&)id=(?<id>[^&]+)')
        $tradeIdMatch = [regex]::Match($matchupTradeHref, '(?:\?|&)opponent=(?<id>[^&]+)')
        if (-not $scoutIdMatch.Success -or -not $tradeIdMatch.Success) {
            throw 'BF-926 FAILED: Matchup opponent action IDs could not be parsed.'
        }
        $scoutOpponentId = [System.Uri]::UnescapeDataString($scoutIdMatch.Groups['id'].Value)
        $tradeOpponentId = [System.Uri]::UnescapeDataString($tradeIdMatch.Groups['id'].Value)
        if ($scoutOpponentId -cne $tradeOpponentId) {
            throw "BF-926 FAILED: Matchup opponent actions changed team context. scout=$scoutOpponentId trade=$tradeOpponentId"
        }

        $matchupScout = Invoke-Get -Url ($root + $matchupScoutHref) -TimeoutMs $timeoutMs
        Assert-Status -Response $matchupScout -Expected 200 -Stage 'Matchup opponent Franchise Scout'
        Assert-Markers -Html $matchupScout.Body -Stage 'Matchup opponent Franchise Scout' -Markers @('Franchise Detail','Franchise snapshot','Scout this franchise','READ ONLY')
        Assert-NoRawDeveloperFailure -Html $matchupScout.Body -Stage 'Matchup opponent Franchise Scout'
        Write-Pass -Label 'Matchup opponent Franchise Scout'

        $matchupTrade = Invoke-Get -Url ($root + $matchupTradeHref) -TimeoutMs $timeoutMs
        Assert-Status -Response $matchupTrade -Expected 200 -Stage 'Matchup opponent Trade Analyzer'
        Assert-Markers -Html $matchupTrade.Body -Stage 'Matchup opponent Trade Analyzer' -Markers @('Analyze a trade','Build the deal','Trade partner','Scout franchise','READ ONLY')
        $matchupTradeScoutHref = Get-FirstSafeHref -Html $matchupTrade.Body -Pattern 'href="(?<href>/franchise\?id=[^"]+)"'
        if ([string]::IsNullOrWhiteSpace([string]$matchupTradeScoutHref) -or $matchupTradeScoutHref -cne $matchupScoutHref) {
            throw "BF-926 FAILED: Matchup Trade Analyzer did not preserve the exact opponent Franchise Scout link. expected=$matchupScoutHref actual=$matchupTradeScoutHref"
        }
        Assert-NoRawDeveloperFailure -Html $matchupTrade.Body -Stage 'Matchup opponent Trade Analyzer'
        Write-Pass -Label 'Matchup opponent Trade Analyzer'
    }
    else {
        Assert-AbsentMarkers -Html $matchup.Body -Stage 'Matchup unavailable opponent actions' -Markers @('Scout opponent','Trade with opponent')
    }

    Write-Pass -Label 'Matchup'

    $playerSearch = Invoke-Get -Url ($root + '/players') -TimeoutMs $timeoutMs
    Assert-Status -Response $playerSearch -Expected 200 -Stage 'Player Search'
    Assert-Markers -Html $playerSearch.Body -Stage 'Player Search' -Markers @('Find a rostered player','Quick position searches','href="/players?q=QB">QB</a>','href="/players?q=RB">RB</a>','href="/players?q=WR">WR</a>','href="/players?q=TE">TE</a>','Back to My Team','Back to League')
    Assert-NoRawDeveloperFailure -Html $playerSearch.Body -Stage 'Player Search'
    Write-Pass -Label 'Player Search'

    $playerHref = Get-FirstSafeHref -Html $team.Body -Pattern 'href="(?<href>/player\?id=[^"]+)"'
    if ([string]::IsNullOrWhiteSpace([string]$playerHref)) {
        Write-Skip -Label 'Player Detail' -Reason 'no exact canonical Player Detail link rendered in current My Team evidence'
    }
    else {
        $player = Invoke-Get -Url ($root + $playerHref) -TimeoutMs $timeoutMs
        if ($player.StatusCode -ne 200) {
            $directDiagnostic = Invoke-PlayerDetailDirectDiagnostic -PlayerHref $playerHref
            throw "BF-885 FAILED: Player Detail returned HTTP $($player.StatusCode), expected 200. $directDiagnostic"
        }
        Assert-Markers -Html $player.Body -Stage 'Player Detail' -Markers @('Player Detail','Player snapshot','What do you want to decide?','Find more ','Scout franchise','Open Trade Analyzer','Check Waiver Board','Back to My Team','Player Search','READ ONLY')
        $morePositionHref = Get-FirstSafeHref -Html $player.Body -Pattern 'href="(?<href>/players\?q=[^"]+)">Find more [^<]+</a>'
        if ([string]::IsNullOrWhiteSpace([string]$morePositionHref)) {
            throw 'BF-922 FAILED: Player Detail did not render an exact same-position Player Search shortcut.'
        }
        $positionSearch = Invoke-Get -Url ($root + $morePositionHref) -TimeoutMs $timeoutMs
        Assert-Status -Response $positionSearch -Expected 200 -Stage 'Player position discovery'
        Assert-Markers -Html $positionSearch.Body -Stage 'Player position discovery' -Markers @('Find a rostered player','Search results','Rostered players','READ ONLY')
        Assert-NoRawDeveloperFailure -Html $positionSearch.Body -Stage 'Player position discovery'
        Write-Pass -Label 'Player position discovery'
        $playerCompareCount = [regex]::Matches($player.Body, '>Compare this player</a>').Count
        if ($playerCompareCount -ne 1) {
            throw "BF-917 FAILED: Player Detail expected exactly one Compare this player action, found $playerCompareCount."
        }

        $compareHref = Get-FirstSafeHref -Html $player.Body -Pattern 'href="(?<href>/compare\?left=[^"]+)">Compare this player</a>'
        if ([string]::IsNullOrWhiteSpace([string]$compareHref)) {
            throw 'BF-923 FAILED: Player Detail did not expose the exact Player Compare entry path.'
        }
        $compareStart = Invoke-Get -Url ($root + $compareHref) -TimeoutMs $timeoutMs
        Assert-Status -Response $compareStart -Expected 200 -Stage 'Player Compare start'
        Assert-Markers -Html $compareStart.Body -Stage 'Player Compare start' -Markers @('First player selected','Find same-position players','Choose a different first player','NOT A RANKING')
        Assert-NoRawDeveloperFailure -Html $compareStart.Body -Stage 'Player Compare start'

        $samePositionCompareHref = Get-FirstSafeHref -Html $compareStart.Body -Pattern 'href="(?<href>/compare\?left=[^"]+&q=[^"]+)">Find same-position players</a>'
        if ([string]::IsNullOrWhiteSpace([string]$samePositionCompareHref)) {
            throw 'BF-923 FAILED: Player Compare did not expose the same-position second-player search.'
        }
        $compareChoices = Invoke-Get -Url ($root + $samePositionCompareHref) -TimeoutMs $timeoutMs
        Assert-Status -Response $compareChoices -Expected 200 -Stage 'Player Compare choices'
        Assert-Markers -Html $compareChoices.Body -Stage 'Player Compare choices' -Markers @('First player selected','Choose a player to compare','Compare with this player','NOT A RANKING')
        Assert-NoRawDeveloperFailure -Html $compareChoices.Body -Stage 'Player Compare choices'

        $compareResultHref = Get-FirstSafeHref -Html $compareChoices.Body -Pattern 'href="(?<href>/compare\?left=[^"]+&right=[^"]+)">Compare with this player</a>'
        if ([string]::IsNullOrWhiteSpace([string]$compareResultHref)) {
            throw 'BF-923 FAILED: same-position Player Compare search did not expose an exact second-player result.'
        }
        $compareResult = Invoke-Get -Url ($root + $compareResultHref) -TimeoutMs $timeoutMs
        Assert-Status -Response $compareResult -Expected 200 -Stage 'Player Compare result'
        Assert-Markers -Html $compareResult.Body -Stage 'Player Compare result' -Markers @('Side-by-side neutral evidence','Swap sides','Compare with another ','Comparison evidence','READ ONLY')
        Assert-NoRawDeveloperFailure -Html $compareResult.Body -Stage 'Player Compare result'

        $swapHref = Get-FirstSafeHref -Html $compareResult.Body -Pattern 'href="(?<href>/compare\?left=[^"]+&right=[^"]+)">Swap sides</a>'
        if ([string]::IsNullOrWhiteSpace([string]$swapHref)) {
            throw 'BF-923 FAILED: Player Compare result did not expose an exact swap path.'
        }
        $swappedCompare = Invoke-Get -Url ($root + $swapHref) -TimeoutMs $timeoutMs
        Assert-Status -Response $swappedCompare -Expected 200 -Stage 'Player Compare swapped'
        Assert-Markers -Html $swappedCompare.Body -Stage 'Player Compare swapped' -Markers @('Side-by-side neutral evidence','Swap sides','Compare with another ','Comparison evidence','READ ONLY')
        Assert-NoRawDeveloperFailure -Html $swappedCompare.Body -Stage 'Player Compare swapped'
        Write-Pass -Label 'Player Compare workflow'

        Assert-NoRawDeveloperFailure -Html $player.Body -Stage 'Player Detail'
        Write-Pass -Label 'Player Detail'
    }

    $league = Invoke-Get -Url ($root + '/league') -TimeoutMs $timeoutMs
    Assert-Status -Response $league -Expected 200 -Stage 'League'
    Assert-Markers -Html $league.Body -Stage 'League' -Markers @('League hub','Top franchise snapshot','Comparable movement','READ ONLY')
    Assert-Markers -Html $league.Body -Stage 'League franchise actions' -Markers @('Scout franchise','Open Trade Analyzer')
    Assert-AbsentMarkers -Html $league.Body -Stage 'League' -Markers @('fantasy-team=')
    Assert-PrimaryNavigation -Html $league.Body -Stage 'League'
    Assert-NoRawDeveloperFailure -Html $league.Body -Stage 'League'

    $leagueTradeHref = Get-FirstSafeHref -Html $league.Body -Pattern 'href="(?<href>/trade)">Open Trade Analyzer</a>'
    if ([string]::IsNullOrWhiteSpace([string]$leagueTradeHref)) {
        throw 'BF-924 FAILED: League Hub franchise card did not expose the safe Trade Analyzer entry.'
    }
    $leagueTrade = Invoke-Get -Url ($root + $leagueTradeHref) -TimeoutMs $timeoutMs
    Assert-Status -Response $leagueTrade -Expected 200 -Stage 'League direct Trade Analyzer'
    Assert-Markers -Html $leagueTrade.Body -Stage 'League direct Trade Analyzer' -Markers @('Opening Trade Analyzer...','content="1;url=/trade?load=1"','READ ONLY')
    Assert-NoRawDeveloperFailure -Html $leagueTrade.Body -Stage 'League direct Trade Analyzer'

    $leagueTradeWorkspace = Invoke-Get -Url ($root + '/trade?load=1') -TimeoutMs $timeoutMs
    Assert-Status -Response $leagueTradeWorkspace -Expected 200 -Stage 'League direct Trade Analyzer workspace'
    Assert-Markers -Html $leagueTradeWorkspace.Body -Stage 'League direct Trade Analyzer workspace' -Markers @('Analyze a trade','Trade partner','READ ONLY')
    Assert-NoRawDeveloperFailure -Html $leagueTradeWorkspace.Body -Stage 'League direct Trade Analyzer workspace'
    Write-Pass -Label 'League direct Trade Analyzer'

    $franchiseHref = Get-FirstSafeHref -Html $league.Body -Pattern 'href="(?<href>/franchise\?id=[^"]+)">Scout franchise</a>'
    if ([string]::IsNullOrWhiteSpace([string]$franchiseHref)) {
        Write-Skip -Label 'Franchise Detail' -Reason 'no exact Franchise Detail link rendered in current League evidence'
    }
    else {
        $franchise = Invoke-Get -Url ($root + $franchiseHref) -TimeoutMs $timeoutMs
        Assert-Status -Response $franchise -Expected 200 -Stage 'Franchise Detail'
        Assert-Markers -Html $franchise.Body -Stage 'Franchise Detail' -Markers @('Franchise Detail','Franchise snapshot','Scout this franchise','Open Trade Analyzer','Back to League','READ ONLY')
        Write-Pass -Label 'League direct Franchise Scout'
        $scoutTradeHref = Get-FirstSafeHref -Html $franchise.Body -Pattern 'href="(?<href>/trade\?opponent=[^"]+)"'
        if ([string]::IsNullOrWhiteSpace([string]$scoutTradeHref)) {
            throw 'BF-920 FAILED: Franchise Scout did not render an exact Trade Analyzer opponent link.'
        }
        $scoutTrade = Invoke-Get -Url ($root + $scoutTradeHref) -TimeoutMs $timeoutMs
        Assert-Status -Response $scoutTrade -Expected 200 -Stage 'Franchise Scout Trade Analyzer'
        Assert-Markers -Html $scoutTrade.Body -Stage 'Franchise Scout Trade Analyzer' -Markers @('Analyze a trade','Build the deal','Trade partner','Scout franchise','READ ONLY')
        $tradeScoutHref = Get-FirstSafeHref -Html $scoutTrade.Body -Pattern 'href="(?<href>/franchise\?id=[^"]+)"'
        if ([string]::IsNullOrWhiteSpace([string]$tradeScoutHref)) {
            throw 'BF-921 FAILED: loaded Trade Analyzer did not link back to the exact Franchise Scout.'
        }
        if ($tradeScoutHref -cne $franchiseHref) {
            throw "BF-921 FAILED: Trade Analyzer Franchise Scout link changed team context. expected=$franchiseHref actual=$tradeScoutHref"
        }
        $tradeScout = Invoke-Get -Url ($root + $tradeScoutHref) -TimeoutMs $timeoutMs
        Assert-Status -Response $tradeScout -Expected 200 -Stage 'Trade Analyzer Franchise Scout'
        Assert-Markers -Html $tradeScout.Body -Stage 'Trade Analyzer Franchise Scout' -Markers @('Franchise Detail','Franchise snapshot','Scout this franchise','READ ONLY')
        Assert-NoRawDeveloperFailure -Html $scoutTrade.Body -Stage 'Franchise Scout Trade Analyzer'
        Assert-NoRawDeveloperFailure -Html $franchise.Body -Stage 'Franchise Detail'
        Write-Pass -Label 'Franchise Detail'
    }

    $waivers = Invoke-Get -Url ($root + '/waivers') -TimeoutMs $timeoutMs
    Assert-Status -Response $waivers -Expected 200 -Stage 'Waiver Board'
    Assert-Markers -Html $waivers.Body -Stage 'Waiver Board' -Markers @('Butler waiver decision','Next step','Players Butler authorized for review','NOT A RANKING.','READ ONLY')
    $waiverFirstScan = Get-ManagerFirstScanHtml -Html $waivers.Body
    Assert-AbsentMarkers -Html $waiverFirstScan -Stage 'Waiver Board first scan' -Markers @('Current audit ID:','Sleeper ID:','Pair ADD Sleeper ID:','Pair DROP Sleeper ID:')
    Assert-PrimaryNavigation -Html $waivers.Body -Stage 'Waiver Board'
    Assert-NoRawDeveloperFailure -Html $waivers.Body -Stage 'Waiver Board'

    $waiverHistoryHref = Get-FirstSafeHref -Html $waivers.Body -Pattern 'href="(?<href>/history\?load=1)">View Decision History</a>'
    if ([string]::IsNullOrWhiteSpace([string]$waiverHistoryHref)) {
        throw 'BF-925 FAILED: Waiver Board did not expose the direct Decision History action.'
    }
    $waiverHistory = Invoke-Get -Url ($root + $waiverHistoryHref) -TimeoutMs $timeoutMs
    Assert-Status -Response $waiverHistory -Expected 200 -Stage 'Waiver Board direct Decision History'
    Assert-Markers -Html $waiverHistory.Body -Stage 'Waiver Board direct Decision History' -Markers @('Your waiver decision timeline','Review Waiver Board','Back to Dashboard','READ ONLY')
    Assert-NoRawDeveloperFailure -Html $waiverHistory.Body -Stage 'Waiver Board direct Decision History'
    Write-Pass -Label 'Waiver Board direct Decision History'
    Write-Pass -Label 'Waiver Board'

    $trade = Invoke-Get -Url ($root + '/trade?load=1') -TimeoutMs $timeoutMs
    Assert-Status -Response $trade -Expected 200 -Stage 'Trade Analyzer'
    Assert-Markers -Html $trade.Body -Stage 'Trade Analyzer' -Markers @('Analyze a trade','Trade partner','No new trade score is created here.','READ ONLY')
    Assert-PrimaryNavigation -Html $trade.Body -Stage 'Trade Analyzer'
    Assert-NoRawDeveloperFailure -Html $trade.Body -Stage 'Trade Analyzer'
    Write-Pass -Label 'Trade Analyzer'

    $history = $waiverHistory
    Assert-Status -Response $history -Expected 200 -Stage 'Decision History'
    Assert-Markers -Html $history.Body -Stage 'Decision History' -Markers @('Your waiver decision timeline','Latest outcome','Latest recorded','Newest first','READ ONLY')
    Assert-Markers -Html $history.Body -Stage 'Decision History actions' -Markers @('Review Waiver Board','Back to Dashboard')
    $historyFirstScan = Get-ManagerFirstScanHtml -Html $history.Body
    Assert-AbsentMarkers -Html $historyFirstScan -Stage 'Decision History first scan' -Markers @('Provider frame','Recommendation state','Audit:','BF-603 market:','BF-602 waiver:','ADD / DROP Sleeper ids:')
    Assert-PrimaryNavigation -Html $history.Body -Stage 'Decision History'
    Assert-NoRawDeveloperFailure -Html $history.Body -Stage 'Decision History'

    $historyWaiverHref = Get-FirstSafeHref -Html $history.Body -Pattern 'href="(?<href>/waivers)">Review Waiver Board</a>'
    if ([string]::IsNullOrWhiteSpace([string]$historyWaiverHref)) {
        throw 'BF-925 FAILED: Decision History did not expose the direct Waiver Board action.'
    }
    $historyWaiver = Invoke-Get -Url ($root + $historyWaiverHref) -TimeoutMs $timeoutMs
    Assert-Status -Response $historyWaiver -Expected 200 -Stage 'Decision History direct Waiver Board'
    Assert-Markers -Html $historyWaiver.Body -Stage 'Decision History direct Waiver Board' -Markers @('Butler waiver decision','Next step','Players Butler authorized for review','READ ONLY')
    Assert-NoRawDeveloperFailure -Html $historyWaiver.Body -Stage 'Decision History direct Waiver Board'
    Write-Pass -Label 'Decision History direct Waiver Board'
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
    Write-Host 'BF-912 RESULT: FAIL'
    throw $failure
}
if (-not $passed) {
    throw 'BF-885 FAILED: acceptance ended without a result.'
}

Write-Host 'Working tree: CLEAN'
Write-Host 'BF-885 RESULT: COMPLETE'
Write-Host 'BF-912 RESULT: COMPLETE'
