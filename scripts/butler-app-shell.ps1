param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$LeagueId,

    [ValidateRange(1024, 65535)]
    [int]$Port = 8080,

    [switch]$NoBrowser
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$dashboard = Join-Path $scriptDir "butler-dashboard.ps1"
$gradle = Join-Path $repoRoot "gradlew.bat"
$loopback = [System.Net.IPAddress]::Parse("127.0.0.1")

if (-not (Test-Path -LiteralPath $dashboard)) {
    throw "BF-667 BLOCKED: governed Butler dashboard not found at $dashboard"
}
if (-not (Test-Path -LiteralPath $gradle)) {
    throw "BF-667 BLOCKED: Gradle wrapper not found at $gradle"
}

function ConvertTo-HtmlText {
    param([AllowNull()][object]$Value)
    if ($null -eq $Value) { return "none" }
    return [System.Net.WebUtility]::HtmlEncode([string]$Value)
}

function Get-FreeLoopbackPort {
    $probe = [System.Net.Sockets.TcpListener]::new($loopback, 0)
    try {
        $probe.Start()
        return ([System.Net.IPEndPoint]$probe.LocalEndpoint).Port
    }
    finally {
        $probe.Stop()
    }
}

function Start-GovernedDashboard {
    param([Parameter(Mandatory = $true)][int]$InnerPort)

    $powershell = Join-Path $env:SystemRoot "System32\WindowsPowerShell\v1.0\powershell.exe"
    if (-not (Test-Path -LiteralPath $powershell)) {
        throw "BF-667 BLOCKED: Windows PowerShell 5.1 executable not found."
    }

    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $powershell
    $start.Arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$dashboard`" -LeagueId `"$LeagueId`" -Port $InnerPort -NoBrowser"
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::Start($start)
    if ($null -eq $process) {
        throw "BF-667 BLOCKED: unable to start governed Butler dashboard."
    }
    return $process
}

function Wait-ForGovernedDashboard {
    param(
        [Parameter(Mandatory = $true)][int]$InnerPort,
        [Parameter(Mandatory = $true)]$Process
    )

    for ($attempt = 0; $attempt -lt 80; $attempt++) {
        if ($Process.HasExited) {
            throw "BF-667 BLOCKED: governed Butler dashboard exited during app-shell startup."
        }
        try {
            $request = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$InnerPort/health")
            $request.Method = "GET"
            $request.Timeout = 750
            $response = $request.GetResponse()
            try {
                if ([int]$response.StatusCode -eq 200) { return }
            }
            finally {
                $response.Close()
            }
        }
        catch {
        }
        Start-Sleep -Milliseconds 250
    }
    throw "BF-667 BLOCKED: governed Butler dashboard did not become healthy."
}

function Invoke-ButlerReadOnly {
    param(
        [Parameter(Mandatory = $true)][string]$Arguments,
        [Parameter(Mandatory = $true)][string]$BoundaryName
    )
    $previousPreference = $ErrorActionPreference
    $lines = $null
    $exitCode = $null
    try {
        $ErrorActionPreference = "Continue"
        $lines = & $gradle ":bet:bet-cli:run" "--args=$Arguments" 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    $text = ($lines | ForEach-Object { "$_" }) -join "`n"
    if ($exitCode -ne 0) {
        throw "$BoundaryName BLOCKED: governed read-only command failed with Gradle exit code $exitCode.`n$text"
    }
    return $text
}

function Invoke-ButlerReadOnlyTask {
    param(
        [Parameter(Mandatory = $true)][string]$Task,
        [Parameter(Mandatory = $true)][string]$Arguments,
        [Parameter(Mandatory = $true)][string]$BoundaryName
    )
    $previousPreference = $ErrorActionPreference
    $lines = $null
    $exitCode = $null
    try {
        $ErrorActionPreference = "Continue"
        $lines = & $gradle $Task "--args=$Arguments" 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    $text = ($lines | ForEach-Object { "$_" }) -join "`n"
    if ($exitCode -ne 0) {
        throw "$BoundaryName BLOCKED: governed read-only task failed with Gradle exit code $exitCode.`n$text"
    }
    return $text
}

function Invoke-ButlerLeagueOverview {
    # Keep the exact BF-667 source contract visible for regression/audit.
    $previousPreference = $ErrorActionPreference
    $lines = $null
    $exitCode = $null
    try {
        $ErrorActionPreference = "Continue"
        $lines = & $gradle ":bet:bet-cli:run" "--args=league overview $LeagueId" 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    $text = ($lines | ForEach-Object { "$_" }) -join "`n"
    if ($exitCode -ne 0) {
        throw "BF-667 BLOCKED: governed league overview failed with Gradle exit code $exitCode.`n$text"
    }
    return $text
}

function ConvertTo-LeagueOverviewView {
    param([Parameter(Mandatory = $true)][string]$Text)

    $leagueMatch = [regex]::Match($Text, '(?m)^League:\s+(?<name>.*?)\s+\[(?<id>[^\]]+)\]\s*$')
    $statusMatch = [regex]::Match($Text, '(?m)^Status:\s+(?<status>\S+)\s+core-ready=(?<ready>true|false)\s*$')
    $sourceMatch = [regex]::Match($Text, '(?m)^Source:\s+(?<source>.+?)\s*$')
    $attentionMatch = [regex]::Match($Text, '(?m)^Requires attention:\s+(?<attention>true|false)\s*$')
    if (-not $leagueMatch.Success -or -not $statusMatch.Success -or -not $sourceMatch.Success -or -not $attentionMatch.Success) {
        throw "BF-667 BLOCKED: governed league overview is missing required app fields."
    }

    $leaders = @()
    $leaderPattern = '^\s{2}(?<rank>\d+)\.\s+(?<name>.*?)\s+total=(?<total>-?\d+(?:\.\d+)?)\s+players=(?<players>-?\d+(?:\.\d+)?)\s+picks=(?<picks>-?\d+(?:\.\d+)?)\s+\[(?<id>[^\]]+)\]\s*$'
    foreach ($line in ($Text -split "`r?`n")) {
        $match = [regex]::Match($line, $leaderPattern)
        if ($match.Success) {
            $leaders += [pscustomobject]@{
                Rank = [int]$match.Groups['rank'].Value
                Name = $match.Groups['name'].Value.Trim()
                Total = $match.Groups['total'].Value
                Players = $match.Groups['players'].Value
                Picks = $match.Groups['picks'].Value
                TeamId = $match.Groups['id'].Value.Trim()
            }
        }
    }

    $rankingsUnavailable = $Text -match '(?m)^Franchise rankings:\s+unavailable'
    $rankingsAvailable = $Text -match '(?m)^Franchise leaders:\s*$'
    if ($rankingsAvailable -and $leaders.Count -eq 0) {
        throw "BF-667 BLOCKED: franchise leaders were declared available but no governed leader rows parsed."
    }
    if (-not $rankingsAvailable -and -not $rankingsUnavailable) {
        throw "BF-667 BLOCKED: governed franchise-ranking availability is missing."
    }

    $movementMatch = [regex]::Match($Text, '(?m)^Top value movers:\s+(?<previous>\S+)\s+->\s+(?<latest>\S+)\s+coverage=(?<comparable>\d+)/(?<total>\d+)\s+\((?<coverage>[0-9.]+)%\)\s*$')
    $movementUnavailable = $Text -match '(?m)^Value movement:\s+unavailable'
    $movers = @()
    if ($movementMatch.Success) {
        $movementHeaderIndex = $Text.IndexOf($movementMatch.Value, [System.StringComparison]::Ordinal)
        $nextActionsIndex = $Text.IndexOf("Next actions:", $movementHeaderIndex, [System.StringComparison]::Ordinal)
        $movementBlock = if ($nextActionsIndex -gt $movementHeaderIndex) {
            $Text.Substring($movementHeaderIndex + $movementMatch.Value.Length, $nextActionsIndex - ($movementHeaderIndex + $movementMatch.Value.Length))
        } else {
            $Text.Substring($movementHeaderIndex + $movementMatch.Value.Length)
        }
        foreach ($line in ($movementBlock -split "`r?`n")) {
            if ($line -match '^\s{2}[+-][0-9.]+\s+') {
                $movers += $line.Trim()
            }
        }
    }
    elseif (-not $movementUnavailable) {
        throw "BF-667 BLOCKED: governed movement availability is missing."
    }

    $actions = @()
    $allLines = @($Text -split "`r?`n")
    for ($i = 0; $i -lt $allLines.Count; $i++) {
        $actionMatch = [regex]::Match($allLines[$i], '^\s{2}(?<priority>\d+)\.\s+(?<requirement>REQUIRED|OPTIONAL)\s+(?<kind>\S+)\s+(?<description>.+?)\s*$')
        if (-not $actionMatch.Success) { continue }
        $command = $null
        if ($i + 1 -lt $allLines.Count -and $allLines[$i + 1] -match '^\s{5}(?<command>butler\s+.+)$') {
            $command = $Matches['command'].Trim()
        }
        $actions += [pscustomobject]@{
            Priority = [int]$actionMatch.Groups['priority'].Value
            Requirement = $actionMatch.Groups['requirement'].Value
            Kind = $actionMatch.Groups['kind'].Value
            Description = $actionMatch.Groups['description'].Value.Trim()
            Command = $command
        }
    }

    return [pscustomobject]@{
        LeagueName = $leagueMatch.Groups['name'].Value.Trim()
        LeagueId = $leagueMatch.Groups['id'].Value.Trim()
        Status = $statusMatch.Groups['status'].Value
        CoreReady = $statusMatch.Groups['ready'].Value -ceq "true"
        Source = $sourceMatch.Groups['source'].Value.Trim()
        RequiresAttention = $attentionMatch.Groups['attention'].Value -ceq "true"
        RankingsAvailable = $rankingsAvailable
        Leaders = @($leaders)
        MovementAvailable = $movementMatch.Success
        MovementPrevious = if ($movementMatch.Success) { $movementMatch.Groups['previous'].Value } else { "none" }
        MovementLatest = if ($movementMatch.Success) { $movementMatch.Groups['latest'].Value } else { "none" }
        MovementComparable = if ($movementMatch.Success) { $movementMatch.Groups['comparable'].Value } else { "0" }
        MovementTotal = if ($movementMatch.Success) { $movementMatch.Groups['total'].Value } else { "0" }
        MovementCoverage = if ($movementMatch.Success) { $movementMatch.Groups['coverage'].Value } else { "0.0" }
        Movers = @($movers)
        Actions = @($actions)
    }
}

function ConvertTo-RosterContextView {
    param([Parameter(Mandatory = $true)][string]$Text)

    $gate = [regex]::Match($Text, '(?m)^Binding gate state:\s+(?<state>\S+)\s*$')
    $league = [regex]::Match($Text, '(?m)^Bound Sleeper league:\s+(?<id>\S+)\s+\|\s+(?<name>.+?)\s*$')
    $roster = [regex]::Match($Text, '(?m)^Bound roster / role:\s+(?<id>\d+)\s+/\s+(?<role>\S+)\s*$')
    $display = [regex]::Match($Text, '(?m)^Bound display/team:\s+(?<display>.*?)\s+/\s+(?<team>.*?)\s*$')
    $butlerTeam = [regex]::Match($Text, '(?m)^Butler team id/name:\s+(?<id>\S+)\s+/\s+(?<name>.+?)\s*$')
    $season = [regex]::Match($Text, '(?m)^Provider season/status/leg:\s+(?<season>\d+)/(?<status>[^/]+)/(?<leg>.+?)\s*$')
    $counts = [regex]::Match($Text, '(?m)^Target roster players starter/bench/reserve/taxi:\s+(?<total>\d+)\s+\|\s+(?<starter>\d+)/(?<bench>\d+)/(?<reserve>\d+)/(?<taxi>\d+)\s*$')
    if (-not $gate.Success -or $gate.Groups['state'].Value -cne "BOUND_TARGET_LIVE_VERIFIED" -or
        -not $league.Success -or -not $roster.Success -or -not $display.Success -or
        -not $butlerTeam.Success -or -not $season.Success -or -not $counts.Success) {
        throw "BF-668 BLOCKED: governed BF-623/BF-610 roster identity is missing or not live verified."
    }

    $players = @()
    $playerPattern = '^\s{2}(?<sleeper>\S+)\s+\|\s+rosterSlot=(?<slot>\S+)(?:\s+starterOrdinal=(?<ordinal>\d+)\s+lineupSlot=(?<lineup>\S+))?\s+\|\s+mapping=(?<mapping>\S+)\s+\|\s+name=(?<name>.*?)\s+\|\s+pos=(?<pos>.*?)\s+\|\s+nflTeam=(?<team>.*?)\s+\|\s+butlerPlayer=(?<butler>.*)$'
    foreach ($line in ($Text -split "`r?`n")) {
        $match = [regex]::Match($line, $playerPattern)
        if (-not $match.Success) { continue }
        $players += [pscustomobject]@{
            SleeperId = $match.Groups['sleeper'].Value.Trim()
            Slot = $match.Groups['slot'].Value.Trim()
            Ordinal = if ($match.Groups['ordinal'].Success) { $match.Groups['ordinal'].Value } else { "" }
            Lineup = if ($match.Groups['lineup'].Success) { $match.Groups['lineup'].Value.Trim() } else { "" }
            Mapping = $match.Groups['mapping'].Value.Trim()
            Name = $match.Groups['name'].Value.Trim()
            Position = $match.Groups['pos'].Value.Trim()
            NflTeam = $match.Groups['team'].Value.Trim()
            ButlerPlayerId = $match.Groups['butler'].Value.Trim()
        }
    }
    if ($players.Count -eq 0) {
        throw "BF-668 BLOCKED: governed BF-610 roster returned no player rows."
    }

    return [pscustomobject]@{
        SleeperLeagueId = $league.Groups['id'].Value.Trim()
        LeagueName = $league.Groups['name'].Value.Trim()
        RosterId = [int]$roster.Groups['id'].Value
        Role = $roster.Groups['role'].Value
        DisplayName = $display.Groups['display'].Value.Trim()
        TeamName = $display.Groups['team'].Value.Trim()
        ButlerTeamId = $butlerTeam.Groups['id'].Value.Trim()
        ButlerTeamName = $butlerTeam.Groups['name'].Value.Trim()
        Season = [int]$season.Groups['season'].Value
        ProviderStatus = $season.Groups['status'].Value.Trim()
        TotalPlayers = [int]$counts.Groups['total'].Value
        StarterCount = [int]$counts.Groups['starter'].Value
        BenchCount = [int]$counts.Groups['bench'].Value
        ReserveCount = [int]$counts.Groups['reserve'].Value
        TaxiCount = [int]$counts.Groups['taxi'].Value
        Players = @($players)
    }
}

function ConvertTo-TeamContextView {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$TeamId
    )
    $pattern = '(?ms)^(?<name>.+?)\s+rank=(?<rank>\S+)\s+total=(?<total>-?\d+(?:\.\d+)?)\s+players=(?<players>-?\d+(?:\.\d+)?)\s+picks=(?<picks>-?\d+(?:\.\d+)?)\s*$\r?\n^\s{2}coverage=(?<valued>\d+)/(?<assets>\d+)\s+\((?<coverage>[0-9.]+)%\)\s+movement=(?<movement>\S+)\s+movement-coverage=(?<movevalued>\d+)/(?<roster>\d+)\s+\((?<movecoverage>[0-9.]+)%\)\s*$\r?\n^\s{2}movement-counts:\s+risers=(?<risers>\d+)\s+fallers=(?<fallers>\d+)\s+unchanged=(?<unchanged>\d+)\s+team-id=(?<id>\S+)\s*$'
    $matches = [regex]::Matches($Text, $pattern)
    $selected = @($matches | Where-Object { $_.Groups['id'].Value -ceq $TeamId })
    if ($selected.Count -ne 1) {
        throw "BF-668 BLOCKED: governed league team-context did not resolve exactly one target team."
    }
    $m = $selected[0]
    return [pscustomobject]@{
        Name = $m.Groups['name'].Value.Trim()
        Rank = $m.Groups['rank'].Value
        Total = $m.Groups['total'].Value
        Players = $m.Groups['players'].Value
        Picks = $m.Groups['picks'].Value
        Coverage = $m.Groups['coverage'].Value
        Movement = $m.Groups['movement'].Value
        MovementCoverage = $m.Groups['movecoverage'].Value
        Risers = $m.Groups['risers'].Value
        Fallers = $m.Groups['fallers'].Value
        Unchanged = $m.Groups['unchanged'].Value
    }
}

function ConvertTo-RosterStrengthView {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$TeamId
    )
    $available = [regex]::Match($Text, '(?m)^Available:\s+(?<value>true|false)\s*$')
    if (-not $available.Success) {
        throw "BF-668 BLOCKED: governed roster-strength availability is missing."
    }
    if ($available.Groups['value'].Value -cne "true") {
        $reason = [regex]::Match($Text, '(?m)^Reason:\s+(?<reason>.+?)\s*$')
        return [pscustomobject]@{ Available = $false; Reason = if ($reason.Success) { $reason.Groups['reason'].Value.Trim() } else { "Unavailable" } }
    }
    $pattern = '(?ms)^(?<name>.+?):\s+tier=(?<tier>\S+)\s+starter-value=(?<starter>-?\d+(?:\.\d+)?)\s+total-player-value=(?<total>-?\d+(?:\.\d+)?)\s*$\r?\n^\s{2}coverage=(?<valued>\d+)/(?<players>\d+)\s+\((?<coverage>[0-9.]+)%\)\s+stale=(?<stale>\d+)\s+missing=(?<missing>\d+)\s+team-id=(?<id>\S+)\s*$'
    $matches = [regex]::Matches($Text, $pattern)
    $selected = @($matches | Where-Object { $_.Groups['id'].Value -ceq $TeamId })
    if ($selected.Count -ne 1) {
        throw "BF-668 BLOCKED: governed roster-strength output did not resolve exactly one target team."
    }
    $m = $selected[0]
    return [pscustomobject]@{
        Available = $true
        Tier = $m.Groups['tier'].Value
        StarterValue = $m.Groups['starter'].Value
        TotalPlayerValue = $m.Groups['total'].Value
        Coverage = $m.Groups['coverage'].Value
        Stale = $m.Groups['stale'].Value
        Missing = $m.Groups['missing'].Value
    }
}

function ConvertTo-PositionalPressureView {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$TeamId
    )
    $positions = @()
    $current = $null
    $pending = $null
    foreach ($line in ($Text -split "`r?`n")) {
        $section = [regex]::Match($line, '^(?<position>QB|RB|WR|TE)\s+direct-starters=(?<starters>\d+)\s+available=(?<available>true|false)\s*$')
        if ($section.Success) {
            $current = [pscustomobject]@{
                Position = $section.Groups['position'].Value
                DirectStarters = $section.Groups['starters'].Value
                Available = $section.Groups['available'].Value -ceq "true"
                Reason = ""
                Tier = ""
                StarterCoverageValue = ""
                TotalPositionValue = ""
                Players = ""
                Valued = ""
                Stale = ""
                Missing = ""
            }
            $positions += $current
            $pending = $null
            continue
        }
        if ($null -eq $current) { continue }
        $reason = [regex]::Match($line, '^\s{2}Reason:\s+(?<reason>.+?)\s*$')
        if ($reason.Success) {
            $current.Reason = $reason.Groups['reason'].Value.Trim()
            continue
        }
        $primary = [regex]::Match($line, '^\s{2}(?<name>.+?):\s+tier=(?<tier>\S+)\s+starter-coverage-value=(?<starter>-?\d+(?:\.\d+)?)\s+total-position-value=(?<total>-?\d+(?:\.\d+)?)\s*$')
        if ($primary.Success) {
            $pending = $primary
            continue
        }
        if ($null -ne $pending) {
            $detail = [regex]::Match($line, '^\s{4}players=(?<players>\d+)\s+valued=(?<valued>\d+)\s+stale=(?<stale>\d+)\s+missing=(?<missing>\d+)\s+team-id=(?<id>\S+)\s*$')
            if ($detail.Success) {
                if ($detail.Groups['id'].Value -ceq $TeamId) {
                    $current.Tier = $pending.Groups['tier'].Value
                    $current.StarterCoverageValue = $pending.Groups['starter'].Value
                    $current.TotalPositionValue = $pending.Groups['total'].Value
                    $current.Players = $detail.Groups['players'].Value
                    $current.Valued = $detail.Groups['valued'].Value
                    $current.Stale = $detail.Groups['stale'].Value
                    $current.Missing = $detail.Groups['missing'].Value
                }
                $pending = $null
            }
        }
    }
    if ($positions.Count -ne 4) {
        throw "BF-668 BLOCKED: governed positional-pressure output did not contain QB/RB/WR/TE sections."
    }
    foreach ($position in $positions) {
        if ($position.Available -and [string]::IsNullOrWhiteSpace($position.Tier)) {
            throw "BF-668 BLOCKED: governed positional-pressure output did not resolve target team for $($position.Position)."
        }
    }
    return @($positions)
}

function ConvertTo-TeamPostureView {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$TeamId
    )
    $available = [regex]::Match($Text, '(?m)^Available:\s+(?<value>true|false)\s*$')
    if (-not $available.Success) {
        throw "BF-668 BLOCKED: governed team-posture availability is missing."
    }
    if ($available.Groups['value'].Value -cne "true") {
        return [pscustomobject]@{ Available = $false; Competitive = "Unavailable"; Roster = "Unavailable"; Posture = "Unavailable" }
    }
    $pattern = '(?ms)^(?<name>.+?):\s+competitive=(?<competitive>\S+)\s+roster=(?<roster>\S+)\s+posture=(?<posture>\S+)\s*$\r?\n^\s{2}team-id=(?<id>\S+)\s*$'
    $matches = [regex]::Matches($Text, $pattern)
    $selected = @($matches | Where-Object { $_.Groups['id'].Value -ceq $TeamId })
    if ($selected.Count -ne 1) {
        throw "BF-668 BLOCKED: governed team-posture output did not resolve exactly one target team."
    }
    $m = $selected[0]
    return [pscustomobject]@{
        Available = $true
        Competitive = $m.Groups['competitive'].Value
        Roster = $m.Groups['roster'].Value
        Posture = $m.Groups['posture'].Value
    }
}

function ConvertTo-FutureCapitalView {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$TeamId
    )
    $available = [regex]::Match($Text, '(?m)^Available:\s+(?<value>true|false)\s*$')
    if (-not $available.Success) {
        throw "BF-668 BLOCKED: governed future-capital availability is missing."
    }
    if ($available.Groups['value'].Value -cne "true") {
        $reason = [regex]::Match($Text, '(?m)^Reason:\s+(?<reason>.+?)\s*$')
        return [pscustomobject]@{ Available = $false; Reason = if ($reason.Success) { $reason.Groups['reason'].Value.Trim() } else { "Unavailable" }; Seasons = @() }
    }
    $lines = @($Text -split "`r?`n")
    $selected = $null
    $seasons = @()
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $primary = [regex]::Match($lines[$i], '^(?<name>.+?):\s+tier=(?<tier>\S+)\s+value=(?<value>-?\d+(?:\.\d+)?)\s*$')
        if (-not $primary.Success -or $i + 1 -ge $lines.Count) { continue }
        $detail = [regex]::Match($lines[$i + 1], '^\s{2}coverage=(?<valued>\d+)/(?<picks>\d+)\s+\((?<coverage>[0-9.]+)%\)\s+stale=(?<stale>\d+)\s+missing=(?<missing>\d+)\s+team-id=(?<id>\S+)\s*$')
        if (-not $detail.Success -or $detail.Groups['id'].Value -cne $TeamId) { continue }
        $selected = [pscustomobject]@{
            Available = $true
            Tier = $primary.Groups['tier'].Value
            Value = $primary.Groups['value'].Value
            Coverage = $detail.Groups['coverage'].Value
            Stale = $detail.Groups['stale'].Value
            Missing = $detail.Groups['missing'].Value
        }
        for ($j = $i + 2; $j -lt $lines.Count; $j++) {
            $season = [regex]::Match($lines[$j], '^\s{2}(?<season>\d{4}):\s+value=(?<value>-?\d+(?:\.\d+)?)\s+coverage=(?<valued>\d+)/(?<picks>\d+)\s+\((?<coverage>[0-9.]+)%\)\s+stale=(?<stale>\d+)\s+missing=(?<missing>\d+)\s+rounds=(?<rounds>.+?)\s*$')
            if ($season.Success) {
                $seasons += [pscustomobject]@{
                    Season = $season.Groups['season'].Value
                    Value = $season.Groups['value'].Value
                    Coverage = $season.Groups['coverage'].Value
                    Rounds = $season.Groups['rounds'].Value.Trim()
                }
                continue
            }
            if ($lines[$j] -match '^\S') { break }
        }
        break
    }
    if ($null -eq $selected) {
        throw "BF-668 BLOCKED: governed future-capital output did not resolve exactly one target team."
    }
    $selected | Add-Member -NotePropertyName Seasons -NotePropertyValue @($seasons)
    return $selected
}

function Get-AppCss {
    return @'
:root{font-family:Inter,Segoe UI,Arial,sans-serif;color:#f7f8fb;background:#0b1020;line-height:1.45}*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at top,#172447 0,#0b1020 38%,#070b15 100%);min-height:100vh}.shell{max-width:1120px;margin:0 auto;padding:28px 22px 56px}.top{display:flex;justify-content:space-between;gap:20px;align-items:flex-end;margin-bottom:16px}.brand h1{font-size:38px;letter-spacing:.16em;margin:0}.brand p{margin:5px 0 0;color:#9ca9c8}.target{font-size:14px;color:#cbd4eb;text-align:right}.nav{display:flex;gap:8px;margin:0 0 20px;flex-wrap:wrap}.nav a{color:#b9c6e5;text-decoration:none;padding:9px 13px;border:1px solid #28365f;border-radius:10px;background:#0d1630;font-weight:700}.nav a.active{background:#315dca;color:white;border-color:#315dca}.panel{background:rgba(16,24,48,.88);border:1px solid #28365f;border-radius:20px;padding:22px;box-shadow:0 20px 60px rgba(0,0,0,.28);margin-bottom:18px}.eyebrow{font-size:12px;text-transform:uppercase;letter-spacing:.14em;color:#8ea0c7}.statusrow{display:flex;align-items:flex-start;justify-content:space-between;gap:18px}.headline{font-size:28px;margin:6px 0 4px}.lede{color:#cbd4eb;margin:0;max-width:800px}.status{font-weight:800;padding:9px 13px;border-radius:999px;font-size:13px;white-space:nowrap}.good{background:#123d2c;color:#8ff0b9}.warn{background:#4b3713;color:#ffd98b}.danger{background:#4c2028;color:#ffb0bc}.done{background:#1c315c;color:#a9c6ff}.stats{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-top:18px}.stat,.card,.action,.roster-card{padding:16px;border:1px solid #2b3962;border-radius:14px;background:#0d1630}.stat strong{display:block;color:#8797bd;font-size:11px;text-transform:uppercase;letter-spacing:.08em}.stat span{display:block;font-size:19px;font-weight:800;margin-top:5px}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-top:16px}.grid.four{grid-template-columns:repeat(4,minmax(0,1fr))}.card .rank{color:#8ea0c7;font-size:12px;font-weight:800}.card .name,.roster-card .name{font-size:18px;font-weight:800;margin-top:4px}.card .total{font-size:24px;font-weight:900;margin-top:10px}.card .meta,.roster-card .meta{color:#9eabd0;font-size:12px;margin-top:6px}.movers,.actions,.season-list{display:grid;gap:10px;margin-top:16px}.mover,.season-row{padding:13px 15px;border:1px solid #2b3962;border-radius:12px;background:#0d1630;font-family:Consolas,monospace;font-size:12px;color:#cbd4eb}.action-head{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.pill{display:inline-block;padding:4px 7px;border-radius:999px;font-size:10px;font-weight:900}.required{background:#4b3713;color:#ffd98b}.optional{background:#1c315c;color:#a9c6ff}.action .kind{font-weight:800;color:#c7d9ff}.action p{margin:8px 0 0;color:#cbd4eb}.command{display:block;width:100%;margin-top:10px;padding:10px 12px;border-radius:10px;background:#080f20;border:1px solid #26345c;color:#c7d9ff;font-family:Consolas,monospace;font-size:12px}.empty{margin-top:14px;padding:16px;border:1px solid #2b3962;border-radius:14px;background:#0d1630;color:#aebada}.boundary{font-size:13px;color:#a9b5d2}.lock{font-weight:800;color:#a9c6ff}.roster-section{margin-top:20px}.roster-section h3{margin:0 0 10px}.roster-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.slot{display:inline-block;margin-top:8px;padding:4px 7px;border-radius:999px;background:#1c315c;color:#a9c6ff;font-size:10px;font-weight:900}.pressure-tier{font-size:22px;font-weight:900;margin-top:8px}.technical{margin-top:12px;color:#8ea0c7;font-size:12px}details{margin-top:14px;border-top:1px solid #28365f;padding-top:12px}summary{cursor:pointer;color:#a9c6ff;font-weight:700}@media(max-width:860px){.grid.four{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:760px){.top,.statusrow{display:block}.target{text-align:left;margin-top:12px}.brand h1{font-size:30px}.status{display:inline-block;margin-top:12px}.stats,.grid,.grid.four,.roster-grid{grid-template-columns:1fr}}
'@
}

function Get-AppNav {
    param([Parameter(Mandatory = $true)][string]$Active)
    $dashboardClass = if ($Active -ceq "dashboard") { ' class="active"' } else { "" }
    $teamClass = if ($Active -ceq "team") { ' class="active"' } else { "" }
    $waiversClass = if ($Active -ceq "waivers") { ' class="active"' } else { "" }
    $leagueClass = if ($Active -ceq "league") { ' class="active"' } else { "" }
    return "<nav class=`"nav`" aria-label=`"Butler sections`"><a$dashboardClass href=`"/`">Dashboard</a><a$teamClass href=`"/team`">My Team</a><a$waiversClass href=`"/waivers`">Waiver Board</a><a$leagueClass href=`"/league`">League</a></nav>"
}

function ConvertTo-LeagueHtml {
    param([Parameter(Mandatory = $true)]$View)

    $statusClass = switch ($View.Status) {
        "READY" { "good" }
        "PARTIAL" { "warn" }
        "STALE" { "warn" }
        default { "danger" }
    }
    $leadersHtml = ""
    if ($View.RankingsAvailable) {
        foreach ($leader in $View.Leaders) {
            $leadersHtml += "<article class=`"card`"><div class=`"rank`">Rank $(ConvertTo-HtmlText $leader.Rank)</div><div class=`"name`">$(ConvertTo-HtmlText $leader.Name)</div><div class=`"total`">$(ConvertTo-HtmlText $leader.Total)</div><div class=`"meta`">Players $(ConvertTo-HtmlText $leader.Players) &middot; Picks $(ConvertTo-HtmlText $leader.Picks)</div><div class=`"meta`">Team ID $(ConvertTo-HtmlText $leader.TeamId)</div></article>"
        }
    }
    else {
        $leadersHtml = '<div class="empty">Franchise rankings are unavailable until Butler reports current asset coverage as READY. No leader is inferred.</div>'
    }

    $movementHtml = ""
    if ($View.MovementAvailable) {
        if ($View.Movers.Count -eq 0) {
            $movementHtml = '<div class="empty">The governed movement window is available, but no mover rows were returned.</div>'
        }
        else {
            foreach ($mover in $View.Movers) {
                $movementHtml += "<div class=`"mover`">$(ConvertTo-HtmlText $mover)</div>"
            }
        }
    }
    else {
        $movementHtml = '<div class="empty">Value movement is unavailable until comparable provider snapshots exist. Butler does not manufacture a trend.</div>'
    }

    $actionsHtml = ""
    if ($View.Actions.Count -eq 0) {
        $actionsHtml = '<div class="empty">No governed next action is currently required.</div>'
    }
    else {
        foreach ($action in $View.Actions) {
            $pillClass = if ($action.Requirement -ceq "REQUIRED") { "required" } else { "optional" }
            $commandHtml = if ([string]::IsNullOrWhiteSpace($action.Command)) { "" } else { "<input class=`"command`" readonly value=`"$(ConvertTo-HtmlText $action.Command)`">" }
            $actionsHtml += "<article class=`"action`"><div class=`"action-head`"><span class=`"pill $pillClass`">$(ConvertTo-HtmlText $action.Requirement)</span><span class=`"kind`">$(ConvertTo-HtmlText $action.Kind)</span></div><p>$(ConvertTo-HtmlText $action.Description)</p>$commandHtml</article>"
        }
    }

    $attentionText = if ($View.RequiresAttention) { "Needs attention" } else { "No required blocker" }
    $coreText = if ($View.CoreReady) { "Ready" } else { "Not ready" }
    $movementSummary = if ($View.MovementAvailable) {
        "$(ConvertTo-HtmlText $View.MovementPrevious) to $(ConvertTo-HtmlText $View.MovementLatest) &middot; $(ConvertTo-HtmlText $View.MovementComparable)/$(ConvertTo-HtmlText $View.MovementTotal) ($(ConvertTo-HtmlText $View.MovementCoverage)%)"
    } else { "Unavailable" }
    $css = Get-AppCss
    $nav = Get-AppNav -Active "league"

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - League</title><style>$css</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$(ConvertTo-HtmlText $View.LeagueName) &middot; $(ConvertTo-HtmlText $View.LeagueId)</div></header>
$nav
<section class="panel"><div class="eyebrow">League intelligence</div><div class="statusrow"><div><h1 class="headline">$(ConvertTo-HtmlText $View.LeagueName)</h1><p class="lede">Butler's existing governed league overview, presented as an app view without adding a new ranking or strategy model.</p></div><div class="status $statusClass">$(ConvertTo-HtmlText $View.Status)</div></div><div class="stats"><div class="stat"><strong>Core analysis</strong><span>$(ConvertTo-HtmlText $coreText)</span></div><div class="stat"><strong>Source</strong><span>$(ConvertTo-HtmlText $View.Source)</span></div><div class="stat"><strong>Attention</strong><span>$(ConvertTo-HtmlText $attentionText)</span></div></div></section>
<section class="panel"><div class="eyebrow">Safe franchise context</div><h2>Franchise leaders</h2><p class="lede">Shown only when Butler's existing franchise-readiness gate authorizes rankings.</p><div class="grid">$leadersHtml</div></section>
<section class="panel"><div class="eyebrow">Comparable history</div><h2>Value movement</h2><p class="lede">$movementSummary</p><div class="movers">$movementHtml</div></section>
<section class="panel"><div class="eyebrow">Governed guidance</div><h2>Next actions</h2><p class="lede">These are Butler's existing deterministic league-health actions. Commands are displayed for manual use only and are never executed by this page.</p><div class="actions">$actionsHtml</div></section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> BF-667 presents the existing governed league overview. It does not rerank franchises, create strategy labels, refresh values, mutate evidence, run waiver decisions, set FAAB, execute trades, or submit Sleeper transactions.</section>
</main></body></html>
"@
}

function ConvertTo-TeamHtml {
    param(
        [Parameter(Mandatory = $true)]$Roster,
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$Strength,
        [Parameter(Mandatory = $true)]$Pressure,
        [Parameter(Mandatory = $true)]$Posture,
        [Parameter(Mandatory = $true)]$Capital
    )

    $displayTeam = if (-not [string]::IsNullOrWhiteSpace($Roster.TeamName) -and $Roster.TeamName -cne "none") { $Roster.TeamName } else { $Roster.ButlerTeamName }
    $rankText = if ($Context.Rank -eq "-") { "Unavailable" } else { "#$($Context.Rank)" }
    $strengthTier = if ($Strength.Available) { $Strength.Tier } else { "Unavailable" }
    $postureText = if ($Posture.Available) { $Posture.Posture } else { "Unavailable" }
    $capitalTier = if ($Capital.Available) { $Capital.Tier } else { "Unavailable" }

    $pressureHtml = ""
    foreach ($position in $Pressure) {
        if ($position.Available) {
            $pressureHtml += "<article class=`"card`"><div class=`"rank`">$(ConvertTo-HtmlText $position.Position) &middot; $(ConvertTo-HtmlText $position.DirectStarters) direct starter(s)</div><div class=`"pressure-tier`">$(ConvertTo-HtmlText $position.Tier)</div><div class=`"meta`">Starter coverage $(ConvertTo-HtmlText $position.StarterCoverageValue)</div><div class=`"meta`">Total position value $(ConvertTo-HtmlText $position.TotalPositionValue)</div><div class=`"meta`">Players $(ConvertTo-HtmlText $position.Valued)/$(ConvertTo-HtmlText $position.Players) valued &middot; stale $(ConvertTo-HtmlText $position.Stale) &middot; missing $(ConvertTo-HtmlText $position.Missing)</div></article>"
        }
        else {
            $pressureHtml += "<article class=`"card`"><div class=`"rank`">$(ConvertTo-HtmlText $position.Position)</div><div class=`"pressure-tier`">Unavailable</div><div class=`"meta`">$(ConvertTo-HtmlText $position.Reason)</div></article>"
        }
    }

    $seasonHtml = ""
    if ($Capital.Available -and $Capital.Seasons.Count -gt 0) {
        foreach ($season in $Capital.Seasons) {
            $seasonHtml += "<div class=`"season-row`">$(ConvertTo-HtmlText $season.Season) &middot; value $(ConvertTo-HtmlText $season.Value) &middot; coverage $(ConvertTo-HtmlText $season.Coverage)% &middot; $(ConvertTo-HtmlText $season.Rounds)</div>"
        }
    }
    elseif (-not $Capital.Available) {
        $seasonHtml = "<div class=`"empty`">$(ConvertTo-HtmlText $Capital.Reason)</div>"
    }

    $rosterHtml = ""
    foreach ($group in @("QB","RB","WR","TE","K","DEF","OTHER")) {
        $groupPlayers = @($Roster.Players | Where-Object {
            $position = $_.Position
            if ($group -ceq "OTHER") { $position -notin @("QB","RB","WR","TE","K","DEF") } else { $position -ceq $group }
        })
        if ($groupPlayers.Count -eq 0) { continue }
        $cards = ""
        foreach ($player in $groupPlayers) {
            $slotLabel = if ($player.Slot -ceq "STARTER" -and -not [string]::IsNullOrWhiteSpace($player.Lineup)) { "Starter - $($player.Lineup)" } else { $player.Slot }
            $cards += "<article class=`"roster-card`"><div class=`"name`">$(ConvertTo-HtmlText $player.Name)</div><div class=`"meta`">$(ConvertTo-HtmlText $player.Position) &middot; $(ConvertTo-HtmlText $player.NflTeam)</div><span class=`"slot`">$(ConvertTo-HtmlText $slotLabel)</span><details><summary>Technical details</summary><div class=`"technical`">Sleeper $(ConvertTo-HtmlText $player.SleeperId) &middot; Butler $(ConvertTo-HtmlText $player.ButlerPlayerId) &middot; mapping $(ConvertTo-HtmlText $player.Mapping)</div></details></article>"
        }
        $rosterHtml += "<section class=`"roster-section`"><h3>$(ConvertTo-HtmlText $group)</h3><div class=`"roster-grid`">$cards</div></section>"
    }

    $strengthDetail = if ($Strength.Available) {
        "Starter value $(ConvertTo-HtmlText $Strength.StarterValue) &middot; total player value $(ConvertTo-HtmlText $Strength.TotalPlayerValue) &middot; coverage $(ConvertTo-HtmlText $Strength.Coverage)%"
    } else { ConvertTo-HtmlText $Strength.Reason }
    $postureDetail = if ($Posture.Available) {
        "Competitive $(ConvertTo-HtmlText $Posture.Competitive) &middot; roster $(ConvertTo-HtmlText $Posture.Roster)"
    } else { "Governed posture unavailable" }
    $capitalDetail = if ($Capital.Available) {
        "Value $(ConvertTo-HtmlText $Capital.Value) &middot; coverage $(ConvertTo-HtmlText $Capital.Coverage)%"
    } else { ConvertTo-HtmlText $Capital.Reason }

    $css = Get-AppCss
    $nav = Get-AppNav -Active "team"
    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - My Team</title><style>$css</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$(ConvertTo-HtmlText $Roster.LeagueName) &middot; $(ConvertTo-HtmlText $displayTeam) &middot; roster $(ConvertTo-HtmlText $Roster.RosterId)</div></header>
$nav
<section class="panel"><div class="eyebrow">My team intelligence</div><div class="statusrow"><div><h1 class="headline">$(ConvertTo-HtmlText $displayTeam)</h1><p class="lede">Butler's existing governed team evidence composed into one read-only app screen. No new team score or strategy model is created here.</p></div><div class="status good">LIVE VERIFIED</div></div><div class="stats"><div class="stat"><strong>Franchise rank</strong><span>$(ConvertTo-HtmlText $rankText)</span></div><div class="stat"><strong>Total franchise value</strong><span>$(ConvertTo-HtmlText $Context.Total)</span></div><div class="stat"><strong>Roster players</strong><span>$(ConvertTo-HtmlText $Roster.TotalPlayers)</span></div></div></section>
<section class="panel"><div class="eyebrow">Governed dimensions</div><h2>Team snapshot</h2><div class="grid"><article class="card"><div class="rank">Roster strength</div><div class="pressure-tier">$(ConvertTo-HtmlText $strengthTier)</div><div class="meta">$strengthDetail</div></article><article class="card"><div class="rank">Team posture</div><div class="pressure-tier">$(ConvertTo-HtmlText $postureText)</div><div class="meta">$postureDetail</div></article><article class="card"><div class="rank">Future capital</div><div class="pressure-tier">$(ConvertTo-HtmlText $capitalTier)</div><div class="meta">$capitalDetail</div></article></div><details><summary>Team context details</summary><div class="technical">Player value $(ConvertTo-HtmlText $Context.Players) &middot; pick value $(ConvertTo-HtmlText $Context.Picks) &middot; asset coverage $(ConvertTo-HtmlText $Context.Coverage)% &middot; movement $(ConvertTo-HtmlText $Context.Movement) &middot; movement coverage $(ConvertTo-HtmlText $Context.MovementCoverage)% &middot; risers/fallers/unchanged $(ConvertTo-HtmlText $Context.Risers)/$(ConvertTo-HtmlText $Context.Fallers)/$(ConvertTo-HtmlText $Context.Unchanged)</div></details></section>
<section class="panel"><div class="eyebrow">Lineup-aware pressure</div><h2>Position context</h2><p class="lede">These are Butler's existing positional-pressure tiers. FLEX/SUPERFLEX remain separate governed context; this page does not turn them into start/sit advice.</p><div class="grid four">$pressureHtml</div></section>
<section class="panel"><div class="eyebrow">Future flexibility</div><h2>Draft capital by season</h2><div class="season-list">$seasonHtml</div></section>
<section class="panel"><div class="eyebrow">Current roster</div><h2>Players</h2><p class="lede">Exact BF-623-bound live roster context from BF-610. Players are grouped for readability only, not ranked.</p>$rosterHtml</section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> BF-668 composes existing governed team context, roster strength, positional pressure, posture, future capital, and exact roster evidence. It does not create a new score, recommend a lineup, rerank players, refresh evidence, set FAAB, execute trades, or submit Sleeper transactions.</section>
</main></body></html>
"@
}

function Add-LeagueNavigation {
    param([Parameter(Mandatory = $true)][string]$Html)
    if ($Html -match 'href="/league"') { return $Html }
    if ($Html -notmatch '<nav class="nav" aria-label="Butler sections">') {
        throw "BF-667 BLOCKED: governed dashboard HTML is missing the Butler navigation contract."
    }
    return $Html.Replace('</nav>', '<a href="/league">League</a></nav>')
}

function Invoke-GovernedDashboardGet {
    param(
        [Parameter(Mandatory = $true)][int]$InnerPort,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $request = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$InnerPort$Path")
    $request.Method = "GET"
    $request.Timeout = 180000
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
            ContentType = if ([string]::IsNullOrWhiteSpace($response.ContentType)) { "text/plain; charset=utf-8" } else { [string]$response.ContentType }
            Body = $body
        }
    }
    finally {
        if ($null -ne $response) { $response.Close() }
    }
}

function Send-HttpResponse {
    param(
        [Parameter(Mandatory = $true)]$Stream,
        [Parameter(Mandatory = $true)][int]$StatusCode,
        [Parameter(Mandatory = $true)][string]$StatusText,
        [Parameter(Mandatory = $true)][string]$ContentType,
        [Parameter(Mandatory = $true)][string]$Body
    )
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
    $headers = "HTTP/1.1 $StatusCode $StatusText`r`n" +
        "Content-Type: $ContentType`r`n" +
        "Content-Length: $($bodyBytes.Length)`r`n" +
        "Cache-Control: no-store`r`n" +
        "X-Content-Type-Options: nosniff`r`n" +
        "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'`r`n" +
        "Connection: close`r`n`r`n"
    $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($headers)
    $Stream.Write($headerBytes, 0, $headerBytes.Length)
    $Stream.Write($bodyBytes, 0, $bodyBytes.Length)
    $Stream.Flush()
}

$innerPort = Get-FreeLoopbackPort
$dashboardProcess = $null
$listener = [System.Net.Sockets.TcpListener]::new($loopback, $Port)
Push-Location $repoRoot
try {
    $dashboardProcess = Start-GovernedDashboard -InnerPort $innerPort
    Wait-ForGovernedDashboard -InnerPort $innerPort -Process $dashboardProcess
    $listener.Start()

    $url = "http://127.0.0.1:$Port/"
    Write-Host "Butler App Shell (BF-668)"
    Write-Host "Local URL: $url"
    Write-Host "My Team: http://127.0.0.1:$Port/team"
    Write-Host "League: http://127.0.0.1:$Port/league"
    Write-Host "Bind: 127.0.0.1 only"
    Write-Host "Existing governed dashboard: isolated on internal loopback port $innerPort"
    Write-Host "Boundary: read-only app routing/presentation; no automatic Butler or Sleeper write."
    Write-Host "Press Ctrl+C to stop Butler."

    if (-not $NoBrowser) { Start-Process $url }

    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $stream = $client.GetStream()
            $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::ASCII, $false, 8192, $true)
            $requestLine = $reader.ReadLine()
            if ([string]::IsNullOrWhiteSpace($requestLine)) { continue }
            while ($true) {
                $headerLine = $reader.ReadLine()
                if ($null -eq $headerLine -or $headerLine.Length -eq 0) { break }
            }

            $parts = $requestLine.Split(' ')
            if ($parts.Length -lt 2 -or $parts[0] -ne "GET") {
                Send-HttpResponse -Stream $stream -StatusCode 405 -StatusText "Method Not Allowed" -ContentType "text/plain; charset=utf-8" -Body "GET only"
                continue
            }

            $path = $parts[1].Split('?')[0]
            if ($path -eq "/health") {
                Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "application/json; charset=utf-8" -Body '{"status":"ok","service":"butler-app-shell","inner":"ready","bind":"127.0.0.1"}'
                continue
            }

            if ($path -eq "/league") {
                try {
                    $view = ConvertTo-LeagueOverviewView -Text (Invoke-ButlerLeagueOverview)
                    $html = ConvertTo-LeagueHtml -View $view
                    Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "text/html; charset=utf-8" -Body $html
                }
                catch {
                    $errorHtml = "<!doctype html><html><body><h1>Butler League view blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No Butler or Sleeper write was executed.</p></body></html>"
                    Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText "Internal Server Error" -ContentType "text/html; charset=utf-8" -Body $errorHtml
                }
                continue
            }

            if ($path -eq "/team") {
                try {
                    $rosterText = Invoke-ButlerReadOnlyTask -Task ":bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit" -Arguments $LeagueId -BoundaryName "BF-668"
                    $rosterView = ConvertTo-RosterContextView -Text $rosterText
                    $teamId = $rosterView.ButlerTeamId
                    $context = ConvertTo-TeamContextView -Text (Invoke-ButlerReadOnly -Arguments "league team-context $LeagueId" -BoundaryName "BF-668") -TeamId $teamId
                    $strength = ConvertTo-RosterStrengthView -Text (Invoke-ButlerReadOnly -Arguments "league roster-strength $LeagueId" -BoundaryName "BF-668") -TeamId $teamId
                    $pressure = ConvertTo-PositionalPressureView -Text (Invoke-ButlerReadOnly -Arguments "league positional-pressure $LeagueId" -BoundaryName "BF-668") -TeamId $teamId
                    $posture = ConvertTo-TeamPostureView -Text (Invoke-ButlerReadOnly -Arguments "league team-posture $LeagueId $($rosterView.Season)" -BoundaryName "BF-668") -TeamId $teamId
                    $capital = ConvertTo-FutureCapitalView -Text (Invoke-ButlerReadOnly -Arguments "league future-capital $LeagueId" -BoundaryName "BF-668") -TeamId $teamId
                    $html = ConvertTo-TeamHtml -Roster $rosterView -Context $context -Strength $strength -Pressure $pressure -Posture $posture -Capital $capital
                    Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "text/html; charset=utf-8" -Body $html
                }
                catch {
                    $errorHtml = "<!doctype html><html><body><h1>Butler My Team view blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No Butler or Sleeper write was executed.</p></body></html>"
                    Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText "Internal Server Error" -ContentType "text/html; charset=utf-8" -Body $errorHtml
                }
                continue
            }

            $candidate = $path -match '^/waivers/candidate/[0-9]+$'
            $knownDashboardPath = $path -eq "/" -or $path -eq "/index.html" -or $path -eq "/waivers" -or $candidate
            if (-not $knownDashboardPath) {
                Send-HttpResponse -Stream $stream -StatusCode 404 -StatusText "Not Found" -ContentType "text/plain; charset=utf-8" -Body "Not found"
                continue
            }

            try {
                $proxied = Invoke-GovernedDashboardGet -InnerPort $innerPort -Path $path
                $body = $proxied.Body
                if ($proxied.ContentType -match '^text/html') {
                    $body = Add-LeagueNavigation -Html $body
                }
                Send-HttpResponse -Stream $stream -StatusCode $proxied.StatusCode -StatusText $proxied.StatusText -ContentType $proxied.ContentType -Body $body
            }
            catch {
                $errorHtml = "<!doctype html><html><body><h1>Butler app blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No Butler or Sleeper write was executed.</p></body></html>"
                Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText "Internal Server Error" -ContentType "text/html; charset=utf-8" -Body $errorHtml
            }
        }
        finally {
            $client.Close()
        }
    }
}
finally {
    try { $listener.Stop() } catch {}
    if ($null -ne $dashboardProcess -and -not $dashboardProcess.HasExited) {
        try { $dashboardProcess.Kill() } catch {}
        try { $dashboardProcess.WaitForExit(5000) | Out-Null } catch {}
    }
    Pop-Location
}
