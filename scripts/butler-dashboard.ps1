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
        "CURRENT_AND_ACTIONABLE" { return [pscustomobject]@{ Class="good"; Headline="Ready to act"; Copy="Butler verified this move against the live roster and the latest governed evidence."; ActionTitle="What to do"; ActionCopy="Make this add/drop manually in Sleeper if you choose to act. Butler verifies and records the recommendation, but this dashboard never submits the transaction for you." } }
        "TRANSACTION_ALREADY_COMPLETE" { return [pscustomobject]@{ Class="done"; Headline="Move completed"; Copy="Sleeper shows the exact governed transaction as complete. Do not submit it again."; ActionTitle="No action needed"; ActionCopy="This governed move is closed. Butler will keep it for audit history and will not tell you to resubmit it." } }
        "TRANSACTION_PENDING_DO_NOT_DUPLICATE" { return [pscustomobject]@{ Class="warn"; Headline="Move pending"; Copy="Sleeper is already processing this exact transaction. Do not submit a duplicate."; ActionTitle="Wait for Sleeper"; ActionCopy="Do not submit this add/drop again while the exact transaction is pending. Refresh status after Sleeper processes it." } }
        "CURRENT_REFRESH_RECOMMENDED" { return [pscustomobject]@{ Class="warn"; Headline="Refresh recommended"; Copy="The move is still live-actionable, but Butler's persisted waiver evidence is older than the approved six-hour warning threshold."; ActionTitle="Consider refreshing first"; ActionCopy="The six-hour policy is warning-only, not a hard block. A governed evidence refresh is recommended before acting." } }
        "STALE_DO_NOT_ACT" { return [pscustomobject]@{ Class="danger"; Headline="Do not act"; Copy="A hard safety gate failed. Keep this decision only for traceability until Butler produces a new governed result."; ActionTitle="Stop here"; ActionCopy="Do not make this move from the displayed audit. Butler must produce a new governed state before action." } }
        default { return [pscustomobject]@{ Class="danger"; Headline="No actionable move"; Copy="Butler does not currently have a governed transaction that is safe to act on."; ActionTitle="No move to make"; ActionCopy="There is no currently governed add/drop action on this screen." } }
    }
}

function Get-VerificationCopy {
    param([AllowNull()][string]$Bf629, [AllowNull()][string]$Bf631)
    $rosterOk = $Bf629 -eq "LIVE_ACTIONABLE_VERIFIED" -or $Bf629 -eq "AUDITED_TRANSACTION_COMPLETE" -or $Bf629 -eq "AUDITED_TRANSACTION_PENDING"
    $lineageOk = $Bf631 -eq "LATEST_EVIDENCE_LINEAGE_VERIFIED"
    $roster = if ($Bf629 -eq "LIVE_ACTIONABLE_VERIFIED") { "Live roster check passed" } elseif ($Bf629 -eq "AUDITED_TRANSACTION_COMPLETE") { "Completed transaction verified" } elseif ($Bf629 -eq "AUDITED_TRANSACTION_PENDING") { "Pending transaction verified" } else { "Live roster safety gate not green" }
    $lineage = if ($lineageOk) { "Evidence lineage verified" } else { "Evidence lineage not current" }
    return [pscustomobject]@{ Roster=$roster; RosterOk=$rosterOk; Lineage=$lineage; LineageOk=$lineageOk }
}

function Invoke-ButlerReadOnlyTask {
    param(
        [Parameter(Mandatory = $true)][string]$Task,
        [Parameter(Mandatory = $true)][string]$BoundaryName
    )
    $previousPreference = $ErrorActionPreference
    $lines = $null
    $exitCode = $null
    try {
        $ErrorActionPreference = "Continue"
        $lines = & $gradle $Task "--args=$LeagueId" 2>&1
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

function Invoke-ButlerReadOnlySummary {
    return Invoke-ButlerReadOnlyTask -Task ":bet:bet-cli:sleeperLiveWaiverLatestGovernedDecisionSummary" -BoundaryName "BF-643"
}

function Invoke-ButlerReadOnlyRosterContext {
    return Invoke-ButlerReadOnlyTask -Task ":bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit" -BoundaryName "BF-645"
}

function ConvertTo-RosterPlayerView {
    param([Parameter(Mandatory = $true)][string]$Line)
    $pattern = '^\s{2}(?<id>\S+)\s+\|\s+rosterSlot=(?<slot>\S+)(?:\s+starterOrdinal=(?<ordinal>\d+)\s+lineupSlot=(?<lineup>\S+))?\s+\|\s+mapping=(?<mapping>\S+)\s+\|\s+name=(?<name>.*?)\s+\|\s+pos=(?<pos>.*?)\s+\|\s+nflTeam=(?<team>.*?)\s+\|\s+butlerPlayer=(?<butler>.*)$'
    $match = [regex]::Match($Line, $pattern)
    if (-not $match.Success) { return $null }
    return [pscustomobject]@{
        SleeperId = $match.Groups['id'].Value.Trim()
        RosterSlot = $match.Groups['slot'].Value.Trim()
        StarterOrdinal = if ($match.Groups['ordinal'].Success) { $match.Groups['ordinal'].Value.Trim() } else { $null }
        LineupSlot = if ($match.Groups['lineup'].Success) { $match.Groups['lineup'].Value.Trim() } else { $null }
        Mapping = $match.Groups['mapping'].Value.Trim()
        Name = $match.Groups['name'].Value.Trim()
        Position = $match.Groups['pos'].Value.Trim()
        Team = $match.Groups['team'].Value.Trim()
        ButlerPlayer = $match.Groups['butler'].Value.Trim()
    }
}

function Get-RosterPlayers {
    param([Parameter(Mandatory = $true)][string]$RosterContext)
    $players = @()
    foreach ($line in ($RosterContext -split "`r?`n")) {
        if ($line -notmatch '^\s{2}\S+\s+\|\s+rosterSlot=') { continue }
        $player = ConvertTo-RosterPlayerView -Line $line
        if ($null -eq $player) { throw "BF-645 BLOCKED: unable to parse governed BF-610 roster player line: $line" }
        $players += $player
    }
    if ($players.Count -eq 0) { throw "BF-645 BLOCKED: BF-610 returned no governed target-roster players" }
    return @($players)
}

function Get-RosterStatusLabel {
    param($Player)
    switch ($Player.RosterSlot) {
        "STARTER" {
            if ([string]::IsNullOrWhiteSpace($Player.LineupSlot)) { return "Starter" }
            return "Starter · $($Player.LineupSlot)"
        }
        "RESERVE" { return "Reserve" }
        "TAXI" { return "Taxi" }
        "BENCH" { return "Bench" }
        default { return $Player.RosterSlot }
    }
}

function Get-SharedCss {
    return @'
:root{font-family:Inter,Segoe UI,Arial,sans-serif;color:#f7f8fb;background:#0b1020;line-height:1.45}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at top,#172447 0,#0b1020 38%,#070b15 100%);min-height:100vh}.shell{max-width:1120px;margin:0 auto;padding:28px 22px 56px}
.top{display:flex;justify-content:space-between;gap:20px;align-items:flex-end;margin-bottom:16px}.brand h1{font-size:38px;letter-spacing:.16em;margin:0}.brand p{margin:5px 0 0;color:#9ca9c8}.target{font-size:14px;color:#cbd4eb;text-align:right}
.nav{display:flex;gap:8px;margin:0 0 20px}.nav a{color:#b9c6e5;text-decoration:none;padding:9px 13px;border:1px solid #28365f;border-radius:10px;background:#0d1630;font-weight:700}.nav a.active{background:#315dca;color:white;border-color:#315dca}
.panel{background:rgba(16,24,48,.88);border:1px solid #28365f;border-radius:20px;padding:22px;box-shadow:0 20px 60px rgba(0,0,0,.28);margin-bottom:18px}.statusrow{display:flex;align-items:flex-start;justify-content:space-between;gap:18px}.eyebrow{font-size:12px;text-transform:uppercase;letter-spacing:.14em;color:#8ea0c7}.status{font-weight:800;padding:9px 13px;border-radius:999px;font-size:13px;white-space:nowrap}.good{background:#123d2c;color:#8ff0b9}.done{background:#1c315c;color:#a9c6ff}.warn{background:#4b3713;color:#ffd98b}.danger{background:#4c2028;color:#ffb0bc}
.headline{font-size:28px;margin:6px 0 4px}.lede{color:#cbd4eb;margin:0;max-width:760px}.moves{display:grid;grid-template-columns:1fr 1fr;gap:18px;margin-top:20px}.move{border:1px solid #33436f;border-radius:16px;padding:20px;background:#0d1630}.move.add{border-color:#286a4c}.move.drop{border-color:#74414b}.move h2{font-size:12px;letter-spacing:.14em;margin:0 0 10px}.player-name{font-size:25px;font-weight:800;line-height:1.15}.player-meta{margin-top:8px;color:#aebada;font-size:14px}.player-id{margin-top:5px;color:#7485aa;font-size:12px}
.next{margin-top:18px;padding:18px;border-radius:16px;background:#0a142c;border:1px solid #2e467e}.next strong{display:block;font-size:16px;margin-bottom:5px}.next p{margin:0;color:#cbd4eb}.verify-grid,.fresh-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.fresh-grid{margin-top:12px}.verify,.fresh{padding:18px;border-radius:14px;background:#0d1630;border:1px solid #26345c}.check{font-weight:800;color:#8ff0b9}.alert{font-weight:800;color:#ffd98b}.verify small{display:block;color:#8797bd;margin-top:5px}.fresh strong{display:block;color:#9eabd0;font-size:13px}.fresh .age{font-size:22px;font-weight:800;margin-top:5px}.fresh .limit{font-size:12px;color:#8797bd;margin-top:4px}
.lineage{display:flex;justify-content:space-between;gap:20px;align-items:center}.lineage-copy strong,.lineage-copy span{display:block}.lineage-copy strong{font-size:17px}.lineage-copy span{color:#97a7ca;font-size:13px;margin-top:3px}.actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.button{display:inline-block;text-decoration:none;color:#fff;background:#315dca;padding:11px 16px;border-radius:11px;font-weight:700}.subtle{color:#94a2c5;font-size:13px}.boundary{font-size:13px;color:#a9b5d2}.lock{font-weight:800;color:#a9c6ff}
details{margin-top:14px;border-top:1px solid #28365f;padding-top:14px}summary{cursor:pointer;color:#a9b7d7;font-weight:700}.tech{margin-top:12px;display:grid;grid-template-columns:1fr 1fr;gap:8px 18px;font-family:Consolas,monospace;font-size:12px;color:#9eabd0}.tech div{word-break:break-word}.raw-guard{margin-top:12px;padding:12px;border-left:3px solid #536996;background:#0b142b;color:#bfc9e1;font-size:12px}
.position-section{margin-top:20px}.position-head{display:flex;justify-content:space-between;align-items:end;margin-bottom:10px}.position-head h2{margin:0;font-size:21px}.position-count{color:#8797bd;font-size:13px}.roster-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.roster-card{padding:16px;border:1px solid #2b3962;border-radius:14px;background:#0d1630}.roster-card .name{font-size:18px;font-weight:800}.roster-card .meta{color:#aebada;font-size:13px;margin-top:4px}.roster-card .slot{display:inline-block;margin-top:10px;padding:5px 8px;border-radius:999px;background:#17254a;color:#a9c6ff;font-size:11px;font-weight:800}.roster-card .id{color:#6f81aa;font-size:11px;margin-top:8px}.roster-note{color:#9ba8c8;font-size:13px;margin-top:8px}
@media(max-width:760px){.top{display:block}.target{text-align:left;margin-top:12px}.moves,.verify-grid,.fresh-grid,.tech,.roster-grid{grid-template-columns:1fr}.brand h1{font-size:30px}.statusrow{display:block}.status{display:inline-block;margin-top:12px}.lineage{display:block}.lineage .status{margin-top:10px}}
'@
}

function Get-HeaderHtml {
    param([AllowNull()][string]$Target, [Parameter(Mandatory=$true)][string]$Active)
    $dashboardClass = if ($Active -eq "dashboard") { "active" } else { "" }
    $teamClass = if ($Active -eq "team") { "active" } else { "" }
    return @"
<header class="top">
  <div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div>
  <div class="target">$(ConvertTo-HtmlText $Target)</div>
</header>
<nav class="nav" aria-label="Butler sections">
  <a class="$dashboardClass" href="/">Dashboard</a>
  <a class="$teamClass" href="/team">My Team</a>
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
    $css = Get-SharedCss
    $header = Get-HeaderHtml -Target $target -Active "dashboard"

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler Dashboard</title><style>$css</style></head><body><main class="shell">
$header
<section class="panel">
  <div class="statusrow"><div><div class="eyebrow">Current Butler recommendation</div><h2 class="headline">$(ConvertTo-HtmlText $presentation.Headline)</h2><p class="lede">$(ConvertTo-HtmlText $presentation.Copy)</p></div><div class="status $($presentation.Class)">$(ConvertTo-HtmlText $presentation.Headline)</div></div>
  <div class="moves">
    <article class="move add"><h2>ADD</h2><div class="player-name">$(ConvertTo-HtmlText $add.Name)</div><div class="player-meta">$(ConvertTo-HtmlText $add.Position) &middot; $(ConvertTo-HtmlText $add.Team)</div><div class="player-id">Sleeper ID $(ConvertTo-HtmlText $add.SleeperId)</div></article>
    <article class="move drop"><h2>DROP</h2><div class="player-name">$(ConvertTo-HtmlText $drop.Name)</div><div class="player-meta">$(ConvertTo-HtmlText $drop.Position) &middot; $(ConvertTo-HtmlText $drop.Team)</div><div class="player-id">Sleeper ID $(ConvertTo-HtmlText $drop.SleeperId)</div></article>
  </div>
  <div class="next"><strong>$(ConvertTo-HtmlText $presentation.ActionTitle)</strong><p>$(ConvertTo-HtmlText $presentation.ActionCopy)</p></div>
</section>
<section class="panel"><div class="eyebrow">Safety checks</div><h2>Butler verified the decision</h2><div class="verify-grid"><div class="verify"><div class="$rosterClass">$rosterIcon $(ConvertTo-HtmlText $verification.Roster)</div><small>BF-629 checks whether the audited move is still valid against Sleeper.</small></div><div class="verify"><div class="$lineageClass">$lineageIcon $(ConvertTo-HtmlText $verification.Lineage)</div><small>BF-631 proves this audit still points to Butler's latest governed evidence frame.</small></div></div><div class="fresh-grid"><div class="fresh"><strong>Waiver market evidence</strong><div class="age">$(ConvertTo-HtmlText $market.Human)</div><div class="limit">Warning boundary: $thresholdHours hours</div></div><div class="fresh"><strong>Roster / waiver evidence</strong><div class="age">$(ConvertTo-HtmlText $waiver.Human)</div><div class="limit">Warning boundary: $thresholdHours hours</div></div></div></section>
<section class="panel"><div class="eyebrow">Decision record</div><div class="lineage"><div class="lineage-copy"><strong>Immutable Butler audit captured</strong><span>Every governed recommendation remains traceable even after your roster changes.</span></div><div class="status done">AUDITED</div></div><details><summary>Technical details</summary><div class="tech"><div>Decision state: $(ConvertTo-HtmlText $state)</div><div>BF-629: $(ConvertTo-HtmlText $bf629)</div><div>BF-631: $(ConvertTo-HtmlText $bf631)</div><div>BF-633: $(ConvertTo-HtmlText $bf633)</div><div>Audit ID: $(ConvertTo-HtmlText $audit.Id)</div><div>Captured UTC: $(ConvertTo-HtmlText $audit.Captured)</div><div>Telemetry UTC: $(ConvertTo-HtmlText $telemetry)</div><div>Warning threshold: $(ConvertTo-HtmlText $thresholdRaw) sec</div><div>BF-603 observed: $(ConvertTo-HtmlText $market.Observed)</div><div>BF-603 age: $(ConvertTo-HtmlText $market.Seconds) sec</div><div>BF-602 observed: $(ConvertTo-HtmlText $waiver.Observed)</div><div>BF-602 age: $(ConvertTo-HtmlText $waiver.Seconds) sec</div></div><div class="raw-guard">$(ConvertTo-HtmlText $guard)</div></details></section>
<section class="panel"><div class="actions"><a class="button" href="/">Refresh status</a><a class="button" href="/team">View My Team</a><span class="subtle">These views are read-only and do not start BF-641.</span></div></section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> Butler does not refresh evidence, rerank players, capture an audit, set FAAB, submit a Sleeper transaction, cancel a transaction, or mutate your league from this dashboard.</section>
</main></body></html>
"@
}

function ConvertTo-TeamHtml {
    param([Parameter(Mandatory = $true)][string]$RosterContext)
    $players = Get-RosterPlayers -RosterContext $RosterContext
    $owner = Get-LineValue -Text $RosterContext -Label "Provider owner display/team name:"
    $rosterId = Get-LineValue -Text $RosterContext -Label "Exact target roster id:"
    $counts = Get-LineValue -Text $RosterContext -Label "Target roster players starter/bench/reserve/taxi:"
    $slots = Get-LineValue -Text $RosterContext -Label "Live starting slots:"
    $target = "$owner | roster $rosterId"
    $css = Get-SharedCss
    $header = Get-HeaderHtml -Target $target -Active "team"
    $sections = ""
    $positions = @("QB", "RB", "WR", "TE")
    $knownIds = @{}

    foreach ($position in $positions) {
        $group = @($players | Where-Object { $_.Position -eq $position })
        foreach ($p in $group) { $knownIds[$p.SleeperId] = $true }
        if ($group.Count -eq 0) { continue }
        $cards = ""
        foreach ($player in $group) {
            $slotLabel = Get-RosterStatusLabel -Player $player
            $cards += "<article class=`"roster-card`"><div class=`"name`">$(ConvertTo-HtmlText $player.Name)</div><div class=`"meta`">$(ConvertTo-HtmlText $player.Position) &middot; NFL $(ConvertTo-HtmlText $player.Team)</div><div class=`"slot`">$(ConvertTo-HtmlText $slotLabel)</div><div class=`"id`">Sleeper ID $(ConvertTo-HtmlText $player.SleeperId)</div></article>"
        }
        $sections += "<section class=`"position-section`"><div class=`"position-head`"><h2>$position</h2><span class=`"position-count`">$($group.Count) player$(if ($group.Count -ne 1) {'s'} else {''})</span></div><div class=`"roster-grid`">$cards</div></section>"
    }

    $other = @($players | Where-Object { -not $knownIds.ContainsKey($_.SleeperId) })
    if ($other.Count -gt 0) {
        $cards = ""
        foreach ($player in $other) {
            $slotLabel = Get-RosterStatusLabel -Player $player
            $cards += "<article class=`"roster-card`"><div class=`"name`">$(ConvertTo-HtmlText $player.Name)</div><div class=`"meta`">$(ConvertTo-HtmlText $player.Position) &middot; NFL $(ConvertTo-HtmlText $player.Team)</div><div class=`"slot`">$(ConvertTo-HtmlText $slotLabel)</div><div class=`"id`">Sleeper ID $(ConvertTo-HtmlText $player.SleeperId)</div></article>"
        }
        $sections += "<section class=`"position-section`"><div class=`"position-head`"><h2>Other / unmapped position</h2><span class=`"position-count`">$($other.Count)</span></div><div class=`"roster-grid`">$cards</div></section>"
    }

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - My Team</title><style>$css</style></head><body><main class="shell">
$header
<section class="panel"><div class="eyebrow">My Team</div><h1 class="headline">Your exact governed roster</h1><p class="lede">This is BF-610's BF-623-verified target roster, grouped only for display. Position groups and roster labels are not rankings or lineup advice.</p><div class="roster-note">Roster counts: $(ConvertTo-HtmlText $counts)</div><div class="roster-note">Live starting slots: $(ConvertTo-HtmlText $slots)</div>$sections</section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> My Team shows exact governed roster context only. It does not score roster needs, optimize a lineup, rank your players, recommend a drop, set FAAB, or submit a Sleeper transaction.</section>
</main></body></html>
"@
}

function Send-HttpResponse {
    param([Parameter(Mandatory=$true)]$Stream,[Parameter(Mandatory=$true)][int]$StatusCode,[Parameter(Mandatory=$true)][string]$StatusText,[Parameter(Mandatory=$true)][string]$ContentType,[Parameter(Mandatory=$true)][string]$Body)
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
    $headers = "HTTP/1.1 $StatusCode $StatusText`r`n" + "Content-Type: $ContentType`r`n" + "Content-Length: $($bodyBytes.Length)`r`n" + "Cache-Control: no-store`r`n" + "X-Content-Type-Options: nosniff`r`n" + "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'`r`n" + "Connection: close`r`n`r`n"
    $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($headers)
    $Stream.Write($headerBytes,0,$headerBytes.Length); $Stream.Write($bodyBytes,0,$bodyBytes.Length); $Stream.Flush()
}

$listener = [System.Net.Sockets.TcpListener]::new($loopback, $Port)
Push-Location $repoRoot
try {
    $listener.Start()
    $url = "http://127.0.0.1:$Port/"
    Write-Host "BF-643/BF-644/BF-645 Butler Dashboard"
    Write-Host "Local URL: $url"
    Write-Host "My Team: http://127.0.0.1:$Port/team"
    Write-Host "Bind: 127.0.0.1 only"
    Write-Host "Boundary: read-only governed presentation; no BF-641 run and no Sleeper write."
    Write-Host "Press Ctrl+C to stop the dashboard."
    if (-not $NoBrowser) { Start-Process $url }

    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $stream = $client.GetStream()
            $reader = [System.IO.StreamReader]::new($stream,[System.Text.Encoding]::ASCII,$false,8192,$true)
            $requestLine = $reader.ReadLine()
            if ([string]::IsNullOrWhiteSpace($requestLine)) { continue }
            while ($true) { $headerLine = $reader.ReadLine(); if ($null -eq $headerLine -or $headerLine.Length -eq 0) { break } }
            $parts = $requestLine.Split(' ')
            if ($parts.Length -lt 2 -or $parts[0] -ne "GET") { Send-HttpResponse -Stream $stream -StatusCode 405 -StatusText "Method Not Allowed" -ContentType "text/plain; charset=utf-8" -Body "GET only"; continue }
            $path = $parts[1].Split('?')[0]
            if ($path -eq "/health") { Send-HttpResponse -Stream $stream -StatusCode 200 -StatusText "OK" -ContentType "application/json; charset=utf-8" -Body '{"status":"ok","service":"butler-dashboard","bind":"127.0.0.1"}'; continue }
            if ($path -ne "/" -and $path -ne "/index.html" -and $path -ne "/team") { Send-HttpResponse -Stream $stream -StatusCode 404 -StatusText "Not Found" -ContentType "text/plain; charset=utf-8" -Body "Not found"; continue }
            try {
                if ($path -eq "/team") {
                    $rosterContext = Invoke-ButlerReadOnlyRosterContext
                    $html = ConvertTo-TeamHtml -RosterContext $rosterContext
                } else {
                    $summary = Invoke-ButlerReadOnlySummary
                    $html = ConvertTo-DashboardHtml -Summary $summary
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
finally { $listener.Stop(); Pop-Location }
