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
$gradle = Join-Path $repoRoot "gradlew.bat"
$loopback = [System.Net.IPAddress]::Parse("127.0.0.1")

if (-not (Test-Path -LiteralPath $gradle)) {
    throw "BF-643 BLOCKED: Gradle wrapper not found at $gradle"
}

function ConvertTo-HtmlText {
    param([AllowNull()][object]$Value)
    if ($null -eq $Value) { return "none" }
    return [System.Net.WebUtility]::HtmlEncode([string]$Value)
}

function Get-LineValue {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $match = [regex]::Match($Text, "(?m)^" + [regex]::Escape($Label) + "\s*(.*)$")
    if (-not $match.Success) { return $null }
    return $match.Groups[1].Value.Trim()
}

function Invoke-ButlerReadOnlySummary {
    $previousPreference = $ErrorActionPreference
    $lines = $null
    $exitCode = $null
    try {
        # Windows PowerShell 5.1 can promote native stderr warnings to NativeCommandError
        # when ErrorActionPreference is Stop. Gradle exit code remains authoritative.
        $ErrorActionPreference = "Continue"
        $lines = & $gradle ":bet:bet-cli:sleeperLiveWaiverLatestGovernedDecisionSummary" "--args=$LeagueId" 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }

    $text = ($lines | ForEach-Object { "$_" }) -join "`n"
    if ($exitCode -ne 0) {
        throw "BF-643 BLOCKED: governed read-only summary failed with Gradle exit code $exitCode.`n$text"
    }
    return $text
}

function Get-StateClass {
    param([AllowNull()][string]$State)
    switch ($State) {
        "CURRENT_AND_ACTIONABLE" { return "good" }
        "TRANSACTION_ALREADY_COMPLETE" { return "done" }
        "CURRENT_REFRESH_RECOMMENDED" { return "warn" }
        "TRANSACTION_PENDING_DO_NOT_DUPLICATE" { return "warn" }
        default { return "danger" }
    }
}

function ConvertTo-DashboardHtml {
    param([Parameter(Mandatory = $true)][string]$Summary)

    $target = Get-LineValue -Text $Summary -Label "Target:"
    $audit = Get-LineValue -Text $Summary -Label "Audit:"
    $state = Get-LineValue -Text $Summary -Label "Decision status:"
    $bf629 = Get-LineValue -Text $Summary -Label "BF-629 live actionability:"
    $bf631 = Get-LineValue -Text $Summary -Label "BF-631 evidence lineage:"
    $bf633 = Get-LineValue -Text $Summary -Label "BF-633 age telemetry:"
    $threshold = Get-LineValue -Text $Summary -Label "BF-635 refresh-warning threshold seconds:"
    $telemetry = Get-LineValue -Text $Summary -Label "Telemetry observed at UTC:"
    $market = Get-LineValue -Text $Summary -Label "Latest BF-603 observed / age seconds:"
    $waiver = Get-LineValue -Text $Summary -Label "Latest BF-602 observed / age seconds:"
    $add = Get-LineValue -Text $Summary -Label "ADD:"
    $drop = Get-LineValue -Text $Summary -Label "DROP:"
    $guard = Get-LineValue -Text $Summary -Label "Operator guard:"

    $stateClass = Get-StateClass -State $state
    $addText = if ($add) { ConvertTo-HtmlText $add } else { "No governed add" }
    $dropText = if ($drop) { ConvertTo-HtmlText $drop } else { "No governed drop" }

    return @"
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="dark">
<title>Butler Dashboard</title>
<style>
:root{font-family:Inter,Segoe UI,Arial,sans-serif;color:#f7f8fb;background:#0b1020;line-height:1.45}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at top,#172447 0,#0b1020 38%,#070b15 100%);min-height:100vh}
.shell{max-width:1120px;margin:0 auto;padding:36px 22px 56px}.top{display:flex;justify-content:space-between;gap:20px;align-items:flex-end;margin-bottom:24px}
.brand h1{font-size:38px;letter-spacing:.16em;margin:0}.brand p{margin:5px 0 0;color:#9ca9c8}.target{font-size:14px;color:#cbd4eb;text-align:right}
.panel{background:rgba(16,24,48,.88);border:1px solid #28365f;border-radius:20px;padding:22px;box-shadow:0 20px 60px rgba(0,0,0,.28);margin-bottom:18px}
.statusrow{display:flex;align-items:center;justify-content:space-between;gap:16px}.eyebrow{font-size:12px;text-transform:uppercase;letter-spacing:.14em;color:#8ea0c7}.status{font-weight:800;padding:9px 13px;border-radius:999px;font-size:13px}.good{background:#123d2c;color:#8ff0b9}.done{background:#1c315c;color:#a9c6ff}.warn{background:#4b3713;color:#ffd98b}.danger{background:#4c2028;color:#ffb0bc}
.moves{display:grid;grid-template-columns:1fr 1fr;gap:18px;margin-top:18px}.move{border:1px solid #33436f;border-radius:16px;padding:20px;background:#0d1630}.move.add{border-color:#286a4c}.move.drop{border-color:#74414b}.move h2{font-size:13px;letter-spacing:.14em;margin:0 0 10px}.player{font-size:22px;font-weight:750}
.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.metric{padding:16px;border-radius:14px;background:#0d1630;border:1px solid #26345c}.metric strong{display:block;font-size:13px;color:#9eabd0;margin-bottom:5px}.metric span{word-break:break-word}
.audit{font-family:Consolas,monospace;font-size:13px;color:#b9c6e5}.guard{margin-top:16px;padding:14px 16px;border-left:3px solid #7e92c7;background:#0b142b;color:#dce4f7}.actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.button{display:inline-block;text-decoration:none;color:#fff;background:#315dca;padding:11px 16px;border-radius:11px;font-weight:700}.subtle{color:#94a2c5;font-size:13px}.boundary{font-size:13px;color:#a9b5d2}.lock{font-weight:800;color:#a9c6ff}
@media(max-width:760px){.top{display:block}.target{text-align:left;margin-top:12px}.moves,.grid{grid-template-columns:1fr}.brand h1{font-size:30px}}
</style>
</head>
<body>
<main class="shell">
  <header class="top">
    <div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div>
    <div class="target">$(ConvertTo-HtmlText $target)</div>
  </header>

  <section class="panel">
    <div class="statusrow">
      <div><div class="eyebrow">Current governed recommendation</div><h2 style="margin:6px 0 0">Latest Butler decision</h2></div>
      <div class="status $stateClass">$(ConvertTo-HtmlText $state)</div>
    </div>
    <div class="moves">
      <article class="move add"><h2>ADD</h2><div class="player">$addText</div></article>
      <article class="move drop"><h2>DROP</h2><div class="player">$dropText</div></article>
    </div>
    <div class="guard">$(ConvertTo-HtmlText $guard)</div>
  </section>

  <section class="panel">
    <div class="eyebrow">Governance & safety</div>
    <h2>Live verification</h2>
    <div class="grid">
      <div class="metric"><strong>BF-629 live actionability</strong><span>$(ConvertTo-HtmlText $bf629)</span></div>
      <div class="metric"><strong>BF-631 evidence lineage</strong><span>$(ConvertTo-HtmlText $bf631)</span></div>
      <div class="metric"><strong>BF-633 age telemetry</strong><span>$(ConvertTo-HtmlText $bf633)</span></div>
      <div class="metric"><strong>BF-635 warning threshold</strong><span>$(ConvertTo-HtmlText $threshold) seconds</span></div>
      <div class="metric"><strong>BF-603 market evidence</strong><span>$(ConvertTo-HtmlText $market)</span></div>
      <div class="metric"><strong>BF-602 waiver evidence</strong><span>$(ConvertTo-HtmlText $waiver)</span></div>
    </div>
  </section>

  <section class="panel">
    <div class="eyebrow">Immutable audit</div>
    <h2>Decision lineage</h2>
    <div class="audit">$(ConvertTo-HtmlText $audit)</div>
    <div class="subtle" style="margin-top:10px">Telemetry observed: $(ConvertTo-HtmlText $telemetry)</div>
  </section>

  <section class="panel">
    <div class="actions">
      <a class="button" href="/">Refresh read-only status</a>
      <span class="subtle">Refresh reloads only the governed compact summary. It does not run BF-641.</span>
    </div>
  </section>

  <section class="panel boundary">
    <span class="lock">READ-ONLY FOUNDATION.</span> This dashboard does not refresh evidence, rerank players, capture an audit, set FAAB, submit a Sleeper transaction, cancel a transaction, or mutate your league. The Butler Java engine remains authoritative.
  </section>
</main>
</body>
</html>
"@
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

$listener = [System.Net.Sockets.TcpListener]::new($loopback, $Port)
Push-Location $repoRoot
try {
    $listener.Start()
    $url = "http://127.0.0.1:$Port/"
    Write-Host "BF-643 Butler Dashboard"
    Write-Host "Local URL: $url"
    Write-Host "Bind: 127.0.0.1 only"
    Write-Host "Boundary: read-only governed presentation; no BF-641 run and no Sleeper write."
    Write-Host "Press Ctrl+C to stop the dashboard."

    if (-not $NoBrowser) {
        Start-Process $url
    }

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
                Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "application/json; charset=utf-8" -Body '{"status":"ok","service":"butler-dashboard","bind":"127.0.0.1"}'
                continue
            }

            if ($path -ne "/" -and $path -ne "/index.html") {
                Send-HttpResponse -Stream $stream -StatusCode 404 -StatusText "Not Found" -ContentType "text/plain; charset=utf-8" -Body "Not found"
                continue
            }

            try {
                $summary = Invoke-ButlerReadOnlySummary
                $html = ConvertTo-DashboardHtml -Summary $summary
                Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "text/html; charset=utf-8" -Body $html
            }
            catch {
                $errorHtml = "<!doctype html><html><body><h1>Butler dashboard blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No Butler write step was executed.</p></body></html>"
                Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText "Internal Server Error" -ContentType "text/html; charset=utf-8" -Body $errorHtml
            }
        }
        finally {
            $client.Close()
        }
    }
}
finally {
    $listener.Stop()
    Pop-Location
}
