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

function Invoke-ButlerReadOnlyTask {
    param(
        [Parameter(Mandatory = $true)][string]$Task,
        [Parameter(Mandatory = $true)][string]$Boundary
    )
    $previousPreference = $ErrorActionPreference
    $lines = $null
    $exitCode = $null
    try {
        # Windows PowerShell 5.1 can promote harmless native stderr warnings to
        # NativeCommandError. The Gradle process exit code remains authoritative.
        $ErrorActionPreference = "Continue"
        $lines = & $gradle $Task "--args=$LeagueId" 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }

    $text = ($lines | ForEach-Object { "$_" }) -join "`n"
    if ($exitCode -ne 0) {
        throw "$Boundary BLOCKED: read-only Butler task failed with Gradle exit code $exitCode.`n$text"
    }
    return $text
}

function Invoke-ButlerReadOnlySummary {
    return Invoke-ButlerReadOnlyTask -Task ":bet:bet-cli:sleeperLiveWaiverLatestGovernedDecisionSummary" -Boundary "BF-643/BF-644"
}

function Invoke-ButlerReadOnlyRoster {
    return Invoke-ButlerReadOnlyTask -Task ":bet:bet-cli:sleeperPersonalizedCurrentRosterSummary" -Boundary "BF-645"
}

function ConvertTo-PlayerView {
    param([AllowNull()][string]$Line)
    if ([string]::IsNullOrWhiteSpace($Line)) {
        return [pscustomobject]@{ Name = "No governed player"; Position = ""; Team = ""; SleeperId = "" }
    }
    $parts = $Line -split '\s*\|\s*'
    $name = if ($parts.Count -gt 0) { $parts[0].Trim() } else { "Unknown player" }
    $position = if ($parts.Count -gt 1) { $parts[1].Trim() } else { "" }
    $team = ""
    $sleeperId = ""
    foreach ($part in $parts) {
        if ($part -match '^NFL=(.*)$') { $team = $Matches[1].Trim() }
        if ($part -match '^Sleeper=(.*)$') { $sleeperId = $Matches[1].Trim() }
    }
    return [pscustomobject]@{ Name = $name; Position = $position; Team = $team; SleeperId = $sleeperId }
}

function Format-Age {
    param([AllowNull()][object]$Seconds)
    if ($null -eq $Seconds) { return "Unavailable" }
    $value = [long]$Seconds
    if ($value -lt 60) { return "$value sec ago" }
    if ($value -lt 3600) { return "$([math]::Floor($value / 60)) min ago" }
    $hours = [math]::Floor($value / 3600)
    $minutes = [math]::Floor(($value % 3600) / 60)
    if ($minutes -eq 0) { return "$hours hr ago" }
    return "$hours hr $minutes min ago"
}

function ConvertTo-AgeView {
    param([AllowNull()][string]$Line)
    if ([string]::IsNullOrWhiteSpace($Line)) {
        return [pscustomobject]@{ Observed = "none"; Seconds = $null; Human = "Unavailable" }
    }
    $parts = $Line -split '\s*/\s*'
    $observed = if ($parts.Count -gt 0) { $parts[0].Trim() } else { "none" }
    $seconds = $null
    if ($parts.Count -gt 1) {
        $parsed = 0L
        if ([long]::TryParse($parts[1].Trim(), [ref]$parsed)) { $seconds = $parsed }
    }
    return [pscustomobject]@{ Observed = $observed; Seconds = $seconds; Human = (Format-Age -Seconds $seconds) }
}

function ConvertTo-AuditView {
    param([AllowNull()][string]$Line)
    if ([string]::IsNullOrWhiteSpace($Line)) {
        return [pscustomobject]@{ Id = "none"; Captured = "none" }
    }
    $parts = $Line -split '\s*\|\s*'
    $id = if ($parts.Count -gt 0) { $parts[0].Trim() } else { "none" }
    $captured = "none"
    foreach ($part in $parts) {
        if ($part -match '^captured=(.*)$') { $captured = $Matches[1].Trim() }
    }
    return [pscustomobject]@{ Id = $id; Captured = $captured }
}

function Get-StatePresentation {
    param([AllowNull()][string]$State)
    switch ($State) {
        "CURRENT_AND_ACTIONABLE" {
            return [pscustomobject]@{
                Class = "good"; Headline = "Ready to act";
                Copy = "Butler verified this move against the live roster and the latest governed evidence.";
                ActionTitle = "What to do";
                ActionCopy = "Make this add/drop manually in Sleeper if you choose to act. Butler verifies and records the recommendation, but this dashboard never submits the transaction for you."
            }
        }
        "TRANSACTION_ALREADY_COMPLETE" {
            return [pscustomobject]@{
                Class = "done"; Headline = "Move completed";
                Copy = "Sleeper shows the exact governed transaction as complete. Do not submit it again.";
                ActionTitle = "No action needed";
                ActionCopy = "This governed move is closed. Butler will keep it for audit history and will not tell you to resubmit it."
            }
        }
        "TRANSACTION_PENDING_DO_NOT_DUPLICATE" {
            return [pscustomobject]@{
                Class = "warn"; Headline = "Move pending";
                Copy = "Sleeper is already processing this exact transaction. Do not submit a duplicate.";
                ActionTitle = "Wait for Sleeper";
                ActionCopy = "Do not submit this add/drop again while the exact transaction is pending. Refresh status after Sleeper processes it."
            }
        }
        "CURRENT_REFRESH_RECOMMENDED" {
            return [pscustomobject]@{
                Class = "warn"; Headline = "Refresh recommended";
                Copy = "The move is still live-actionable, but Butler's persisted waiver evidence is older than the approved six-hour warning threshold.";
                ActionTitle = "Consider refreshing first";
                ActionCopy = "The six-hour policy is warning-only, not a hard block. A governed evidence refresh is recommended before acting."
            }
        }
        "STALE_DO_NOT_ACT" {
            return [pscustomobject]@{
                Class = "danger"; Headline = "Do not act";
                Copy = "A hard safety gate failed. Keep this decision only for traceability until Butler produces a new governed result.";
                ActionTitle = "Stop here";
                ActionCopy = "Do not make this move from the displayed audit. Butler must produce a new governed state before action."
            }
        }
        default {
            return [pscustomobject]@{
                Class = "danger"; Headline = "No actionable move";
                Copy = "Butler does not currently have a governed transaction that is safe to act on.";
                ActionTitle = "No move to make";
                ActionCopy = "There is no currently governed add/drop action on this screen."
            }
        }
    }
}

function Get-VerificationCopy {
    param([AllowNull()][string]$Bf629, [AllowNull()][string]$Bf631)
    $rosterOk = $Bf629 -eq "LIVE_ACTIONABLE_VERIFIED" -or $Bf629 -eq "AUDITED_TRANSACTION_COMPLETE" -or $Bf629 -eq "AUDITED_TRANSACTION_PENDING"
    $lineageOk = $Bf631 -eq "LATEST_EVIDENCE_LINEAGE_VERIFIED"
    $roster = if ($Bf629 -eq "LIVE_ACTIONABLE_VERIFIED") { "Live roster check passed" } elseif ($Bf629 -eq "AUDITED_TRANSACTION_COMPLETE") { "Completed transaction verified" } elseif ($Bf629 -eq "AUDITED_TRANSACTION_PENDING") { "Pending transaction verified" } else { "Live roster safety gate not green" }
    $lineage = if ($lineageOk) { "Evidence lineage verified" } else { "Evidence lineage not current" }
    return [pscustomobject]@{ Roster = $roster; RosterOk = $rosterOk; Lineage = $lineage; LineageOk = $lineageOk }
}

function Get-DashboardStyle {
    return @"
:root{font-family:Inter,Segoe UI,Arial,sans-serif;color:#f7f8fb;background:#0b1020;line-height:1.45}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at top,#172447 0,#0b1020 38%,#070b15 100%);min-height:100vh}
.shell{max-width:1120px;margin:0 auto;padding:30px 22px 56px}.top{display:flex;justify-content:space-between;gap:20px;align-items:flex-end;margin-bottom:18px}
.brand h1{font-size:38px;letter-spacing:.16em;margin:0}.brand p{margin:5px 0 0;color:#9ca9c8}.target{font-size:14px;color:#cbd4eb;text-align:right}
.nav{display:flex;gap:8px;margin-bottom:20px;padding:6px;background:#0c1429;border:1px solid #25345b;border-radius:13px;width:max-content}.nav a{color:#9faccc;text-decoration:none;padding:9px 14px;border-radius:9px;font-weight:750;font-size:14px}.nav a.active{background:#315dca;color:#fff}
.panel{background:rgba(16,24,48,.88);border:1px solid #28365f;border-radius:20px;padding:22px;box-shadow:0 20px 60px rgba(0,0,0,.28);margin-bottom:18px}
.statusrow{display:flex;align-items:flex-start;justify-content:space-between;gap:18px}.eyebrow{font-size:12px;text-transform:uppercase;letter-spacing:.14em;color:#8ea0c7}.status{font-weight:800;padding:9px 13px;border-radius:999px;font-size:13px;white-space:nowrap}.good{background:#123d2c;color:#8ff0b9}.done{background:#1c315c;color:#a9c6ff}.warn{background:#4b3713;color:#ffd98b}.danger{background:#4c2028;color:#ffb0bc}
.headline{font-size:28px;margin:6px 0 4px}.lede{color:#cbd4eb;margin:0;max-width:760px}.moves{display:grid;grid-template-columns:1fr 1fr;gap:18px;margin-top:20px}.move{border:1px solid #33436f;border-radius:16px;padding:20px;background:#0d1630}.move.add{border-color:#286a4c}.move.drop{border-color:#74414b}.move h2{font-size:12px;letter-spacing:.14em;margin:0 0 10px}.player-name{font-size:25px;font-weight:800;line-height:1.15}.player-meta{margin-top:8px;color:#aebada;font-size:14px}.player-id{margin-top:5px;color:#7485aa;font-size:12px}
.next{margin-top:18px;padding:18px;border-radius:16px;background:#0a142c;border:1px solid #2e467e}.next strong{display:block;font-size:16px;margin-bottom:5px}.next p{margin:0;color:#cbd4eb}
.verify-grid,.fresh-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.fresh-grid{margin-top:12px}.verify,.fresh{padding:18px;border-radius:14px;background:#0d1630;border:1px solid #26345c}.check{font-weight:800;color:#8ff0b9}.alert{font-weight:800;color:#ffd98b}.verify small{display:block;color:#8797bd;margin-top:5px}.fresh strong{display:block;color:#9eabd0;font-size:13px}.fresh .age{font-size:22px;font-weight:800;margin-top:5px}.fresh .limit{font-size:12px;color:#8797bd;margin-top:4px}
.lineage{display:flex;justify-content:space-between;gap:20px;align-items:center}.lineage strong{font-size:17px}.lineage-copy{display:block;color:#97a7ca;font-size:13px;margin-top:5px}.actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.button{display:inline-block;text-decoration:none;color:#fff;background:#315dca;padding:11px 16px;border-radius:11px;font-weight:700}.subtle{color:#94a2c5;font-size:13px}.boundary{font-size:13px;color:#a9b5d2}.lock{font-weight:800;color:#a9c6ff}
details{margin-top:14px;border-top:1px solid #28365f;padding-top:14px}summary{cursor:pointer;color:#a9b7d7;font-weight:700}.tech{margin-top:12px;display:grid;grid-template-columns:1fr 1fr;gap:8px 18px;font-family:Consolas,monospace;font-size:12px;color:#9eabd0}.tech div{word-break:break-word}.raw-guard{margin-top:12px;padding:12px;border-left:3px solid #536996;background:#0b142b;color:#bfc9e1;font-size:12px}
.roster-summary{display:grid;grid-template-columns:repeat(5,1fr);gap:10px;margin-top:18px}.roster-stat{background:#0d1630;border:1px solid #26345c;border-radius:13px;padding:13px}.roster-stat strong{display:block;font-size:21px}.roster-stat span{color:#8fa0c5;font-size:12px}
.position-section{margin-top:18px}.position-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px}.position-head h2{margin:0;font-size:21px}.count{background:#1a2b52;color:#a9c6ff;font-size:12px;font-weight:800;padding:5px 9px;border-radius:999px}.roster-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.roster-card{background:#0d1630;border:1px solid #26345c;border-radius:14px;padding:16px}.roster-card.starter{border-color:#315d7d}.roster-card.reserve{border-color:#66502b}.roster-top{display:flex;justify-content:space-between;gap:10px;align-items:flex-start}.roster-name{font-size:18px;font-weight:800}.role{font-size:10px;font-weight:900;letter-spacing:.08em;padding:5px 8px;border-radius:999px;background:#1b2d54;color:#aac5ff}.role.reserve{background:#4b3713;color:#ffd98b}.role.taxi{background:#34314e;color:#d7c8ff}.roster-sub{color:#9eabd0;font-size:13px;margin-top:5px}.slot{display:inline-block;margin-top:9px;background:#12213f;color:#a9c6ff;border:1px solid #2b4677;padding:4px 7px;border-radius:7px;font-size:11px;font-weight:750}.unmapped{color:#ffd98b}.observed{color:#8797bd;font-size:12px;margin-top:8px}
@media(max-width:760px){.top{display:block}.target{text-align:left;margin-top:12px}.moves,.verify-grid,.fresh-grid,.tech,.roster-grid{grid-template-columns:1fr}.roster-summary{grid-template-columns:repeat(2,1fr)}.brand h1{font-size:30px}.statusrow{display:block}.status{display:inline-block;margin-top:12px}.lineage{display:block}.lineage .status{margin-top:10px}}
"@
}

function Get-HeaderHtml {
    param(
        [AllowNull()][string]$Target,
        [Parameter(Mandatory = $true)][ValidateSet("decision","roster")][string]$Active
    )
    $decisionClass = if ($Active -eq "decision") { "active" } else { "" }
    $rosterClass = if ($Active -eq "roster") { "active" } else { "" }
    return @"
<header class="top">
  <div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div>
  <div class="target">$(ConvertTo-HtmlText $Target)</div>
</header>
<nav class="nav" aria-label="Butler dashboard">
  <a class="$decisionClass" href="/">Decision</a>
  <a class="$rosterClass" href="/roster">My Team</a>
</nav>
"@
}

function ConvertTo-DashboardHtml {
    param([Parameter(Mandatory = $true)][string]$Summary)

    $target = Get-LineValue -Text $Summary -Label "Target:"
    $auditRaw = Get-LineValue -Text $Summary -Label "Audit:"
    $state = Get-LineValue -Text $Summary -Label "Decision status:"
    $bf629 = Get-LineValue -Text $Summary -Label "BF-629 live actionability:"
    $bf631 = Get-LineValue -Text $Summary -Label "BF-631 evidence lineage:"
    $bf633 = Get-LineValue -Text $Summary -Label "BF-633 age telemetry:"
    $thresholdRaw = Get-LineValue -Text $Summary -Label "BF-635 refresh-warning threshold seconds:"
    $telemetry = Get-LineValue -Text $Summary -Label "Telemetry observed at UTC:"
    $marketRaw = Get-LineValue -Text $Summary -Label "Latest BF-603 observed / age seconds:"
    $waiverRaw = Get-LineValue -Text $Summary -Label "Latest BF-602 observed / age seconds:"
    $addRaw = Get-LineValue -Text $Summary -Label "ADD:"
    $dropRaw = Get-LineValue -Text $Summary -Label "DROP:"
    $guard = Get-LineValue -Text $Summary -Label "Operator guard:"

    $add = ConvertTo-PlayerView $addRaw
    $drop = ConvertTo-PlayerView $dropRaw
    $market = ConvertTo-AgeView $marketRaw
    $waiver = ConvertTo-AgeView $waiverRaw
    $audit = ConvertTo-AuditView $auditRaw
    $presentation = Get-StatePresentation $state
    $verification = Get-VerificationCopy -Bf629 $bf629 -Bf631 $bf631
    $rosterIcon = if ($verification.RosterOk) { "&#10003;" } else { "&#9888;" }
    $rosterClass = if ($verification.RosterOk) { "check" } else { "alert" }
    $lineageIcon = if ($verification.LineageOk) { "&#10003;" } else { "&#9888;" }
    $lineageClass = if ($verification.LineageOk) { "check" } else { "alert" }

    $threshold = 21600L
    $parsedThreshold = 0L
    if ([long]::TryParse([string]$thresholdRaw, [ref]$parsedThreshold)) { $threshold = $parsedThreshold }
    $thresholdHours = [math]::Round($threshold / 3600, 1)
    $style = Get-DashboardStyle
    $header = Get-HeaderHtml -Target $target -Active "decision"

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler Dashboard</title><style>$style</style></head>
<body><main class="shell">$header
  <section class="panel">
    <div class="statusrow"><div><div class="eyebrow">Current Butler recommendation</div><h2 class="headline">$(ConvertTo-HtmlText $presentation.Headline)</h2><p class="lede">$(ConvertTo-HtmlText $presentation.Copy)</p></div><div class="status $($presentation.Class)">$(ConvertTo-HtmlText $presentation.Headline)</div></div>
    <div class="moves">
      <article class="move add"><h2>ADD</h2><div class="player-name">$(ConvertTo-HtmlText $add.Name)</div><div class="player-meta">$(ConvertTo-HtmlText $add.Position) &middot; $(ConvertTo-HtmlText $add.Team)</div><div class="player-id">Sleeper ID $(ConvertTo-HtmlText $add.SleeperId)</div></article>
      <article class="move drop"><h2>DROP</h2><div class="player-name">$(ConvertTo-HtmlText $drop.Name)</div><div class="player-meta">$(ConvertTo-HtmlText $drop.Position) &middot; $(ConvertTo-HtmlText $drop.Team)</div><div class="player-id">Sleeper ID $(ConvertTo-HtmlText $drop.SleeperId)</div></article>
    </div>
    <div class="next"><strong>$(ConvertTo-HtmlText $presentation.ActionTitle)</strong><p>$(ConvertTo-HtmlText $presentation.ActionCopy)</p></div>
  </section>

  <section class="panel"><div class="eyebrow">Safety checks</div><h2>Butler verified the decision</h2>
    <div class="verify-grid"><div class="verify"><div class="$rosterClass">$rosterIcon $(ConvertTo-HtmlText $verification.Roster)</div><small>BF-629 checks whether the audited move is still valid against Sleeper.</small></div><div class="verify"><div class="$lineageClass">$lineageIcon $(ConvertTo-HtmlText $verification.Lineage)</div><small>BF-631 proves this audit still points to Butler's latest governed evidence frame.</small></div></div>
    <div class="fresh-grid"><div class="fresh"><strong>Waiver market evidence</strong><div class="age">$(ConvertTo-HtmlText $market.Human)</div><div class="limit">Warning boundary: $thresholdHours hours</div></div><div class="fresh"><strong>Roster / waiver evidence</strong><div class="age">$(ConvertTo-HtmlText $waiver.Human)</div><div class="limit">Warning boundary: $thresholdHours hours</div></div></div>
  </section>

  <section class="panel"><div class="eyebrow">Decision record</div><div class="lineage"><div><strong>Immutable Butler audit captured</strong><span class="lineage-copy">Every governed recommendation remains traceable even after your roster changes.</span></div><div class="status done">AUDITED</div></div>
    <details><summary>Technical details</summary><div class="tech"><div>Decision state: $(ConvertTo-HtmlText $state)</div><div>BF-629: $(ConvertTo-HtmlText $bf629)</div><div>BF-631: $(ConvertTo-HtmlText $bf631)</div><div>BF-633: $(ConvertTo-HtmlText $bf633)</div><div>Audit ID: $(ConvertTo-HtmlText $audit.Id)</div><div>Captured UTC: $(ConvertTo-HtmlText $audit.Captured)</div><div>Telemetry UTC: $(ConvertTo-HtmlText $telemetry)</div><div>Warning threshold: $(ConvertTo-HtmlText $thresholdRaw) sec</div><div>BF-603 observed: $(ConvertTo-HtmlText $market.Observed)</div><div>BF-603 age: $(ConvertTo-HtmlText $market.Seconds) sec</div><div>BF-602 observed: $(ConvertTo-HtmlText $waiver.Observed)</div><div>BF-602 age: $(ConvertTo-HtmlText $waiver.Seconds) sec</div></div><div class="raw-guard">$(ConvertTo-HtmlText $guard)</div></details>
  </section>

  <section class="panel"><div class="actions"><a class="button" href="/">Refresh status</a><a class="button" href="/roster">View My Team</a><span class="subtle">These pages run read-only Butler checks only. They do not start BF-641 or change your roster.</span></div></section>
  <section class="panel boundary"><span class="lock">READ ONLY.</span> Butler does not refresh evidence, rerank players, capture an audit, set FAAB, submit a Sleeper transaction, cancel a transaction, or mutate your league from this dashboard.</section>
</main></body></html>
"@
}

function Get-RosterPlayerCardHtml {
    param([Parameter(Mandatory = $true)]$Player)
    $mapped = [string]$Player.mappingState -eq "EXACT_PERSISTED_ID_MAPPED"
    $name = if ($mapped) { [string]$Player.displayName } else { "Unmapped Sleeper identity" }
    $position = if ($mapped) { [string]$Player.position } else { "Unknown position" }
    $team = if ($mapped -and $null -ne $Player.nflTeam -and -not [string]::IsNullOrWhiteSpace([string]$Player.nflTeam)) { [string]$Player.nflTeam } else { "No NFL team" }
    $role = [string]$Player.role
    $roleClass = if ($role -eq "RESERVE") { "reserve" } elseif ($role -eq "TAXI") { "taxi" } else { "" }
    $cardClass = if ($role -eq "STARTER") { "starter" } elseif ($role -eq "RESERVE") { "reserve" } else { "" }
    $slot = ""
    if ($role -eq "STARTER" -and $null -ne $Player.lineupSlot -and -not [string]::IsNullOrWhiteSpace([string]$Player.lineupSlot)) {
        $slot = "<span class=`"slot`">Lineup: $(ConvertTo-HtmlText $Player.lineupSlot)</span>"
    }
    $nameClass = if ($mapped) { "roster-name" } else { "roster-name unmapped" }
    return @"
<article class="roster-card $cardClass"><div class="roster-top"><div><div class="$nameClass">$(ConvertTo-HtmlText $name)</div><div class="roster-sub">$(ConvertTo-HtmlText $position) &middot; $(ConvertTo-HtmlText $team)</div></div><span class="role $roleClass">$(ConvertTo-HtmlText $role)</span></div>$slot<div class="player-id">Sleeper ID $(ConvertTo-HtmlText $Player.sleeperPlayerId)</div></article>
"@
}

function Get-RosterPositionSectionHtml {
    param(
        [Parameter(Mandatory = $true)]$Report,
        [Parameter(Mandatory = $true)][string]$Position,
        [Parameter(Mandatory = $true)][string]$Title
    )
    $players = if ($Position -eq "__OTHER__") {
        @($Report.players | Where-Object { $null -eq $_.position -or @("QB","RB","WR","TE") -notcontains [string]$_.position })
    } else {
        @($Report.players | Where-Object { [string]$_.position -eq $Position })
    }
    if ($players.Count -eq 0) { return "" }
    $cards = ($players | ForEach-Object { Get-RosterPlayerCardHtml -Player $_ }) -join "`n"
    return @"
<section class="position-section"><div class="position-head"><h2>$(ConvertTo-HtmlText $Title)</h2><span class="count">$($players.Count)</span></div><div class="roster-grid">$cards</div></section>
"@
}

function ConvertTo-RosterHtml {
    param([Parameter(Mandatory = $true)][string]$RosterOutput)
    $json = Get-LineValue -Text $RosterOutput -Label "BF-645 roster JSON:"
    if ([string]::IsNullOrWhiteSpace($json)) { throw "BF-645 BLOCKED: roster JSON line is missing" }
    try { $report = $json | ConvertFrom-Json } catch { throw "BF-645 BLOCKED: roster JSON could not be parsed: $($_.Exception.Message)" }
    if ([string]$report.state -ne "LIVE_CURRENT_ROSTER_VERIFIED") { throw "BF-645 BLOCKED: roster report is not live-current verified" }

    $players = @($report.players)
    $starterCount = @($players | Where-Object { [string]$_.role -eq "STARTER" }).Count
    $benchCount = @($players | Where-Object { [string]$_.role -eq "BENCH" }).Count
    $reserveCount = @($players | Where-Object { [string]$_.role -eq "RESERVE" }).Count
    $taxiCount = @($players | Where-Object { [string]$_.role -eq "TAXI" }).Count
    $target = "$(ConvertTo-HtmlText $report.leagueName) | $(ConvertTo-HtmlText $report.teamName) | roster $(ConvertTo-HtmlText $report.rosterId)"
    $style = Get-DashboardStyle
    $header = Get-HeaderHtml -Target "$($report.leagueName) | $($report.teamName) | roster $($report.rosterId)" -Active "roster"
    $qb = Get-RosterPositionSectionHtml -Report $report -Position "QB" -Title "Quarterbacks"
    $rb = Get-RosterPositionSectionHtml -Report $report -Position "RB" -Title "Running backs"
    $wr = Get-RosterPositionSectionHtml -Report $report -Position "WR" -Title "Wide receivers"
    $te = Get-RosterPositionSectionHtml -Report $report -Position "TE" -Title "Tight ends"
    $other = Get-RosterPositionSectionHtml -Report $report -Position "__OTHER__" -Title "Other / unmapped"
    $slotCopy = if ([string]$report.lineupSlotState -eq "STARTER_SLOTS_VERIFIED") { "Starter lineup labels reconcile to Sleeper's current league configuration." } else { "Starter lineup labels are unavailable; Butler did not infer them." }

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - My Team</title><style>$style</style></head>
<body><main class="shell">$header
  <section class="panel"><div class="eyebrow">My Team</div><h1 class="headline">Your live Sleeper roster</h1><p class="lede">This page reads roster 6 directly from Sleeper after BF-623 verifies your exact account, league, and roster binding. It does not rank or grade players.</p>
    <div class="roster-summary"><div class="roster-stat"><strong>$($players.Count)</strong><span>Total</span></div><div class="roster-stat"><strong>$starterCount</strong><span>Starters</span></div><div class="roster-stat"><strong>$benchCount</strong><span>Bench</span></div><div class="roster-stat"><strong>$reserveCount</strong><span>Reserve</span></div><div class="roster-stat"><strong>$taxiCount</strong><span>Taxi</span></div></div>
    <div class="observed">Live roster observed: $(ConvertTo-HtmlText $report.observedAtUtc) &middot; $(ConvertTo-HtmlText $slotCopy)</div>
  </section>
  <section class="panel">$qb$rb$wr$te$other</section>
  <section class="panel"><div class="actions"><a class="button" href="/roster">Refresh My Team</a><a class="button" href="/">Back to Decision</a><span class="subtle">Refresh rereads the live roster only. It does not run BF-641 or write Butler evidence.</span></div></section>
  <section class="panel boundary"><span class="lock">READ ONLY.</span> BF-645 uses exact Sleeper IDs and the BF-623 verified target. Missing Butler player metadata stays explicitly unmapped; names are never used to infer identity.</section>
</main></body></html>
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
    Write-Host "BF-643/BF-644/BF-645 Butler Dashboard"
    Write-Host "Local URL: $url"
    Write-Host "My Team: http://127.0.0.1:$Port/roster"
    Write-Host "Bind: 127.0.0.1 only"
    Write-Host "Boundary: read-only governed presentation; no BF-641 run and no Sleeper write."
    Write-Host "Press Ctrl+C to stop the dashboard."

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
                Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "application/json; charset=utf-8" -Body '{"status":"ok","service":"butler-dashboard","bind":"127.0.0.1"}'
                continue
            }

            try {
                if ($path -eq "/" -or $path -eq "/index.html") {
                    $html = ConvertTo-DashboardHtml -Summary (Invoke-ButlerReadOnlySummary)
                } elseif ($path -eq "/roster" -or $path -eq "/roster/") {
                    $html = ConvertTo-RosterHtml -RosterOutput (Invoke-ButlerReadOnlyRoster)
                } else {
                    Send-HttpResponse -Stream $stream -StatusCode 404 -StatusText "Not Found" -ContentType "text/plain; charset=utf-8" -Body "Not found"
                    continue
                }
                Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "text/html; charset=utf-8" -Body $html
            }
            catch {
                $errorHtml = "<!doctype html><html><body><h1>Butler dashboard blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No Butler write step was executed.</p></body></html>"
                Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText "Internal Server Error" -ContentType "text/html; charset=utf-8" -Body $errorHtml
            }
        }
        finally { $client.Close() }
    }
}
finally {
    $listener.Stop()
    Pop-Location
}
