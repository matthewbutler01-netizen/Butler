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

function Invoke-ButlerLeagueOverview {
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

function Get-AppCss {
    return @'
:root{font-family:Inter,Segoe UI,Arial,sans-serif;color:#f7f8fb;background:#0b1020;line-height:1.45}*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at top,#172447 0,#0b1020 38%,#070b15 100%);min-height:100vh}.shell{max-width:1120px;margin:0 auto;padding:28px 22px 56px}.top{display:flex;justify-content:space-between;gap:20px;align-items:flex-end;margin-bottom:16px}.brand h1{font-size:38px;letter-spacing:.16em;margin:0}.brand p{margin:5px 0 0;color:#9ca9c8}.target{font-size:14px;color:#cbd4eb;text-align:right}.nav{display:flex;gap:8px;margin:0 0 20px;flex-wrap:wrap}.nav a{color:#b9c6e5;text-decoration:none;padding:9px 13px;border:1px solid #28365f;border-radius:10px;background:#0d1630;font-weight:700}.nav a.active{background:#315dca;color:white;border-color:#315dca}.panel{background:rgba(16,24,48,.88);border:1px solid #28365f;border-radius:20px;padding:22px;box-shadow:0 20px 60px rgba(0,0,0,.28);margin-bottom:18px}.eyebrow{font-size:12px;text-transform:uppercase;letter-spacing:.14em;color:#8ea0c7}.statusrow{display:flex;align-items:flex-start;justify-content:space-between;gap:18px}.headline{font-size:28px;margin:6px 0 4px}.lede{color:#cbd4eb;margin:0;max-width:800px}.status{font-weight:800;padding:9px 13px;border-radius:999px;font-size:13px;white-space:nowrap}.good{background:#123d2c;color:#8ff0b9}.warn{background:#4b3713;color:#ffd98b}.danger{background:#4c2028;color:#ffb0bc}.done{background:#1c315c;color:#a9c6ff}.stats{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-top:18px}.stat,.card,.action{padding:16px;border:1px solid #2b3962;border-radius:14px;background:#0d1630}.stat strong{display:block;color:#8797bd;font-size:11px;text-transform:uppercase;letter-spacing:.08em}.stat span{display:block;font-size:19px;font-weight:800;margin-top:5px}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-top:16px}.card .rank{color:#8ea0c7;font-size:12px;font-weight:800}.card .name{font-size:19px;font-weight:800;margin-top:4px}.card .total{font-size:24px;font-weight:900;margin-top:10px}.card .meta{color:#9eabd0;font-size:12px;margin-top:6px}.movers,.actions{display:grid;gap:10px;margin-top:16px}.mover{padding:13px 15px;border:1px solid #2b3962;border-radius:12px;background:#0d1630;font-family:Consolas,monospace;font-size:12px;color:#cbd4eb}.action-head{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.pill{display:inline-block;padding:4px 7px;border-radius:999px;font-size:10px;font-weight:900}.required{background:#4b3713;color:#ffd98b}.optional{background:#1c315c;color:#a9c6ff}.action .kind{font-weight:800;color:#c7d9ff}.action p{margin:8px 0 0;color:#cbd4eb}.command{display:block;width:100%;margin-top:10px;padding:10px 12px;border-radius:10px;background:#080f20;border:1px solid #26345c;color:#c7d9ff;font-family:Consolas,monospace;font-size:12px}.empty{margin-top:14px;padding:16px;border:1px solid #2b3962;border-radius:14px;background:#0d1630;color:#aebada}.boundary{font-size:13px;color:#a9b5d2}.lock{font-weight:800;color:#a9c6ff}@media(max-width:760px){.top,.statusrow{display:block}.target{text-align:left;margin-top:12px}.brand h1{font-size:30px}.status{display:inline-block;margin-top:12px}.stats,.grid{grid-template-columns:1fr}}
'@
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

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - League</title><style>$css</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">$(ConvertTo-HtmlText $View.LeagueName) &middot; $(ConvertTo-HtmlText $View.LeagueId)</div></header>
<nav class="nav" aria-label="Butler sections"><a href="/">Dashboard</a><a href="/team">My Team</a><a href="/waivers">Waiver Board</a><a class="active" href="/league">League</a></nav>
<section class="panel"><div class="eyebrow">League intelligence</div><div class="statusrow"><div><h1 class="headline">$(ConvertTo-HtmlText $View.LeagueName)</h1><p class="lede">Butler's existing governed league overview, presented as an app view without adding a new ranking or strategy model.</p></div><div class="status $statusClass">$(ConvertTo-HtmlText $View.Status)</div></div><div class="stats"><div class="stat"><strong>Core analysis</strong><span>$(ConvertTo-HtmlText $coreText)</span></div><div class="stat"><strong>Source</strong><span>$(ConvertTo-HtmlText $View.Source)</span></div><div class="stat"><strong>Attention</strong><span>$(ConvertTo-HtmlText $attentionText)</span></div></div></section>
<section class="panel"><div class="eyebrow">Safe franchise context</div><h2>Franchise leaders</h2><p class="lede">Shown only when Butler's existing franchise-readiness gate authorizes rankings.</p><div class="grid">$leadersHtml</div></section>
<section class="panel"><div class="eyebrow">Comparable history</div><h2>Value movement</h2><p class="lede">$movementSummary</p><div class="movers">$movementHtml</div></section>
<section class="panel"><div class="eyebrow">Governed guidance</div><h2>Next actions</h2><p class="lede">These are Butler's existing deterministic league-health actions. Commands are displayed for manual use only and are never executed by this page.</p><div class="actions">$actionsHtml</div></section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> BF-667 presents the existing governed league overview. It does not rerank franchises, create strategy labels, refresh values, mutate evidence, run waiver decisions, set FAAB, execute trades, or submit Sleeper transactions.</section>
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
    Write-Host "Butler App Shell (BF-667)"
    Write-Host "Local URL: $url"
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

            $candidate = $path -match '^/waivers/candidate/[0-9]+$'
            $knownDashboardPath = $path -eq "/" -or $path -eq "/index.html" -or $path -eq "/team" -or $path -eq "/waivers" -or $candidate
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
