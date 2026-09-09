from pathlib import Path

path = Path("scripts/butler-dashboard.ps1")
text = path.read_text(encoding="ascii")

helper = r'''
function Get-CurrentGovernedDropView {
    param(
        [Parameter(Mandatory = $true)][string]$RosterContext,
        [Parameter(Mandatory = $true)][string]$Summary
    )

    $rosterTarget = Get-Bf623TargetView -Text $RosterContext -BoundaryName "BF-651"
    $state = Get-LineValue -Text $Summary -Label "Decision status:"
    if ([string]::IsNullOrWhiteSpace($state)) {
        throw "BF-651 BLOCKED: current governed decision state is missing"
    }

    $audit = ConvertTo-AuditView (Get-LineValue -Text $Summary -Label "Audit:")
    $bf629 = Get-LineValue -Text $Summary -Label "BF-629 live actionability:"
    $bf631 = Get-LineValue -Text $Summary -Label "BF-631 evidence lineage:"
    $active = $state -ceq "CURRENT_AND_ACTIONABLE" -or $state -ceq "CURRENT_REFRESH_RECOMMENDED"

    if (-not $active) {
        return [pscustomobject]@{
            Active = $false
            SleeperId = "none"
            State = $state
            AuditId = $audit.Id
            AuditCaptured = $audit.Captured
            Bf629 = $bf629
            Bf631 = $bf631
            Target = $rosterTarget
            ContextLeagueId = Get-LineValue -Text $RosterContext -Label "Sleeper league:"
            ContextRosterId = Get-LineValue -Text $RosterContext -Label "Exact target roster id:"
            ContextState = Get-LineValue -Text $RosterContext -Label "Target-roster context state:"
        }
    }

    $summaryTarget = Get-Bf623TargetView -Text $Summary -BoundaryName "BF-651"
    foreach ($field in @("SleeperLeagueId", "RosterId", "LeagueName", "DisplayName", "TeamName", "Role")) {
        if ([string]$rosterTarget.$field -cne [string]$summaryTarget.$field) {
            throw "BF-651 BLOCKED: summary BF-623 target identity disagrees with BF-610 BF-623 target identity"
        }
    }

    $contextLeagueId = Get-LineValue -Text $RosterContext -Label "Sleeper league:"
    $contextRosterId = Get-LineValue -Text $RosterContext -Label "Exact target roster id:"
    $contextState = Get-LineValue -Text $RosterContext -Label "Target-roster context state:"
    if ([string]::IsNullOrWhiteSpace($contextLeagueId) -or $contextLeagueId -notmatch '^[0-9]+$') {
        throw "BF-651 BLOCKED: BF-610 raw Sleeper league identity is missing or malformed"
    }
    if ([string]::IsNullOrWhiteSpace($contextRosterId) -or $contextRosterId -notmatch '^[0-9]+$') {
        throw "BF-651 BLOCKED: BF-610 exact target roster identity is missing or malformed"
    }
    if ($contextState -cne "READY_CONTEXT_ONLY") {
        throw "BF-651 BLOCKED: BF-610 target-roster context state is not READY_CONTEXT_ONLY"
    }
    if ($rosterTarget.SleeperLeagueId -cne $contextLeagueId -or $rosterTarget.RosterId -cne $contextRosterId) {
        throw "BF-651 BLOCKED: BF-610 BF-623 target identity disagrees with BF-610 raw roster context"
    }
    if ($bf629 -cne "LIVE_ACTIONABLE_VERIFIED") {
        throw "BF-651 BLOCKED: current decision state does not preserve BF-629 LIVE_ACTIONABLE_VERIFIED"
    }
    if ($bf631 -cne "LATEST_EVIDENCE_LINEAGE_VERIFIED") {
        throw "BF-651 BLOCKED: current decision state does not preserve BF-631 LATEST_EVIDENCE_LINEAGE_VERIFIED"
    }
    if ([string]::IsNullOrWhiteSpace($audit.Id) -or $audit.Id -ceq "none") {
        throw "BF-651 BLOCKED: current audited recommendation identity is missing"
    }

    $drop = ConvertTo-PlayerView (Get-LineValue -Text $Summary -Label "DROP:")
    if ([string]::IsNullOrWhiteSpace($drop.SleeperId) -or $drop.SleeperId -notmatch '^[0-9]+$') {
        throw "BF-651 BLOCKED: current audited DROP exact Sleeper id is missing or malformed"
    }

    $players = @(Get-RosterPlayers -RosterContext $RosterContext)
    $matches = @($players | Where-Object { $_.SleeperId -ceq $drop.SleeperId })
    if ($matches.Count -ne 1) {
        throw "BF-651 BLOCKED: current audited DROP Sleeper id $($drop.SleeperId) must resolve exactly once in BF-610 target roster; found $($matches.Count)"
    }

    return [pscustomobject]@{
        Active = $true
        SleeperId = $drop.SleeperId
        State = $state
        AuditId = $audit.Id
        AuditCaptured = $audit.Captured
        Bf629 = $bf629
        Bf631 = $bf631
        Target = $rosterTarget
        ContextLeagueId = $contextLeagueId
        ContextRosterId = $contextRosterId
        ContextState = $contextState
    }
}

'''
marker = "function Resolve-WaiverCandidateById {"
if marker not in text:
    raise SystemExit("BF-651 helper insertion marker missing")
text = text.replace(marker, helper + marker, 1)

css_old = ".roster-card .id{color:#6f81aa;font-size:11px;margin-top:8px}.roster-note{color:#9ba8c8;font-size:13px;margin-top:8px}"
css_new = ".roster-card .id{color:#6f81aa;font-size:11px;margin-top:8px}.roster-card.current-governed{border-color:#416fda;box-shadow:0 0 0 1px rgba(65,111,218,.28)}.roster-card .current-marker{margin-top:10px}.roster-note{color:#9ba8c8;font-size:13px;margin-top:8px}"
if css_old not in text:
    raise SystemExit("BF-651 CSS marker missing")
text = text.replace(css_old, css_new, 1)

start = text.index("function ConvertTo-TeamHtml {")
end = text.index("function ConvertTo-WaiverHtml {")
team = r'''function ConvertTo-TeamHtml {
    param(
        [Parameter(Mandatory = $true)][string]$RosterContext,
        [Parameter(Mandatory = $true)][string]$Summary
    )
    $players = @(Get-RosterPlayers -RosterContext $RosterContext)
    $current = Get-CurrentGovernedDropView -RosterContext $RosterContext -Summary $Summary
    $counts = Get-LineValue -Text $RosterContext -Label "Target roster players starter/bench/reserve/taxi:"
    $slots = Get-LineValue -Text $RosterContext -Label "Live starting slots:"
    $css = Get-SharedCss
    $header = Get-HeaderHtml -Target $current.Target.Human -Active "team"
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
            $isCurrent = $current.Active -and $player.SleeperId -ceq $current.SleeperId
            $cardClass = if ($isCurrent) { "current-governed" } else { "" }
            $currentBadge = if ($isCurrent) { '<span class="current-marker">Current governed DROP</span>' } else { "" }
            $currentCopy = if ($isCurrent) { '<div class="current-copy">Already-audited current DROP &middot; this marker is not a roster rank or lineup recommendation.</div>' } else { "" }
            $cards += "<article class=`"roster-card $cardClass`"><div class=`"name`">$(ConvertTo-HtmlText $player.Name)</div><div class=`"meta`">$(ConvertTo-HtmlText $player.Position) &middot; NFL $(ConvertTo-HtmlText $player.Team)</div>$currentBadge$currentCopy<div class=`"slot`">$(ConvertTo-HtmlText $slotLabel)</div><div class=`"id`">Sleeper ID $(ConvertTo-HtmlText $player.SleeperId)</div></article>"
        }
        $plural = if ($group.Count -ne 1) { "s" } else { "" }
        $sections += "<section class=`"position-section`"><div class=`"position-head`"><h2>$position</h2><span class=`"position-count`">$($group.Count) player$plural</span></div><div class=`"roster-grid`">$cards</div></section>"
    }

    $other = @($players | Where-Object { -not $knownIds.ContainsKey($_.SleeperId) })
    if ($other.Count -gt 0) {
        $cards = ""
        foreach ($player in $other) {
            $slotLabel = Get-RosterStatusLabel -Player $player
            $isCurrent = $current.Active -and $player.SleeperId -ceq $current.SleeperId
            $cardClass = if ($isCurrent) { "current-governed" } else { "" }
            $currentBadge = if ($isCurrent) { '<span class="current-marker">Current governed DROP</span>' } else { "" }
            $currentCopy = if ($isCurrent) { '<div class="current-copy">Already-audited current DROP &middot; this marker is not a roster rank or lineup recommendation.</div>' } else { "" }
            $cards += "<article class=`"roster-card $cardClass`"><div class=`"name`">$(ConvertTo-HtmlText $player.Name)</div><div class=`"meta`">$(ConvertTo-HtmlText $player.Position) &middot; NFL $(ConvertTo-HtmlText $player.Team)</div>$currentBadge$currentCopy<div class=`"slot`">$(ConvertTo-HtmlText $slotLabel)</div><div class=`"id`">Sleeper ID $(ConvertTo-HtmlText $player.SleeperId)</div></article>"
        }
        $sections += "<section class=`"position-section`"><div class=`"position-head`"><h2>Other / unmapped position</h2><span class=`"position-count`">$($other.Count)</span></div><div class=`"roster-grid`">$cards</div></section>"
    }

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - My Team</title><style>$css</style></head><body><main class="shell">
$header
<section class="panel"><div class="eyebrow">My Team</div><h1 class="headline">Your exact governed roster</h1><p class="lede">This is BF-610's BF-623-verified target roster, grouped only for display. Position groups and roster labels are not rankings or lineup advice.</p><div class="roster-note">Roster counts: $(ConvertTo-HtmlText $counts)</div><div class="roster-note">Live starting slots: $(ConvertTo-HtmlText $slots)</div>$sections<details><summary>Technical details</summary><div class="tech"><div>BF-623 target: $(ConvertTo-HtmlText $current.Target.Human)</div><div>BF-623 target gate: $(ConvertTo-HtmlText $current.Target.Gate)</div><div>BF-623 role: $(ConvertTo-HtmlText $current.Target.Role)</div><div>BF-610 raw Sleeper league / roster: $(ConvertTo-HtmlText $current.ContextLeagueId) / $(ConvertTo-HtmlText $current.ContextRosterId)</div><div>BF-610 context state: $(ConvertTo-HtmlText $current.ContextState)</div><div>Current decision state: $(ConvertTo-HtmlText $current.State)</div><div>Current audit ID: $(ConvertTo-HtmlText $current.AuditId)</div><div>Current DROP Sleeper ID: $(ConvertTo-HtmlText $current.SleeperId)</div><div>BF-629 current gate: $(ConvertTo-HtmlText $current.Bf629)</div><div>BF-631 current gate: $(ConvertTo-HtmlText $current.Bf631)</div></div></details></section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> My Team shows exact governed roster context only. If shown, <strong>Current governed DROP</strong> identifies the already-audited current drop only; it is not a roster rank or lineup recommendation. This page does not score roster needs, optimize a lineup, rank your players, select a new drop, set FAAB, run BF-641, refresh evidence, or submit a Sleeper transaction.</section>
</main></body></html>
"@
}

'''
text = text[:start] + team + text[end:]

route_old = '''                if ($path -eq "/team") {
                    $rosterContext = Invoke-ButlerReadOnlyRosterContext
                    $html = ConvertTo-TeamHtml -RosterContext $rosterContext
                }'''
route_new = '''                if ($path -eq "/team") {
                    $summary = Invoke-ButlerReadOnlySummary
                    $rosterContext = Invoke-ButlerReadOnlyRosterContext
                    $html = ConvertTo-TeamHtml -RosterContext $rosterContext -Summary $summary
                }'''
if route_old not in text:
    raise SystemExit("BF-651 team route marker missing")
text = text.replace(route_old, route_new, 1)

text.encode("ascii")
path.write_text(text, encoding="ascii", newline="\n")
