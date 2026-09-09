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

function Invoke-ButlerReadOnlyWaiverBoard {
    return Invoke-ButlerReadOnlyTask -Task ":bet:bet-cli:sleeperLiveWaiverComparisonBundle" -BoundaryName "BF-646"
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
            return "Starter - $($Player.LineupSlot)"
        }
        "RESERVE" { return "Reserve" }
        "TAXI" { return "Taxi" }
        "BENCH" { return "Bench" }
        default { return $Player.RosterSlot }
    }
}

function ConvertTo-WaiverCandidateView {
    param([Parameter(Mandatory = $true)][string]$Line)
    $pattern = '^\s{2}(?<id>\S+)\s+\|\s+(?<name>.*?)\s+\|\s+pos=(?<pos>.*?)\s+\|\s+lane=(?<lane>.*?)\s+\|\s+team=(?<team>.*?)\s+\|\s+status=(?<status>.*?)\s+\|\s+injury=(?<injury>.*?)\s+\|\s+depth=(?<depth>.*?)\s+\|\s+market add/drop/net=(?<add>-?\d+)/(?<drop>-?\d+)/(?<net>-?\d+)\s+\|\s+candidate-supported-comparators=(?<supported>\[.*?\])\s+\|\s+eligible-comparators=(?<eligible>\[.*\])$'
    $match = [regex]::Match($Line, $pattern)
    if (-not $match.Success) { return $null }
    return [pscustomobject]@{
        SleeperId = $match.Groups['id'].Value.Trim()
        Name = $match.Groups['name'].Value.Trim()
        Position = $match.Groups['pos'].Value.Trim()
        Lane = $match.Groups['lane'].Value.Trim()
        Team = $match.Groups['team'].Value.Trim()
        Status = $match.Groups['status'].Value.Trim()
        Injury = $match.Groups['injury'].Value.Trim()
        Depth = $match.Groups['depth'].Value.Trim()
        MarketAdd = $match.Groups['add'].Value.Trim()
        MarketDrop = $match.Groups['drop'].Value.Trim()
        MarketNet = $match.Groups['net'].Value.Trim()
        SupportedComparators = $match.Groups['supported'].Value.Trim()
        EligibleComparators = $match.Groups['eligible'].Value.Trim()
    }
}

function Get-WaiverCandidates {
    param([Parameter(Mandatory = $true)][string]$Bundle)
    $candidates = @()
    foreach ($line in ($Bundle -split "`r?`n")) {
        if ($line -notmatch '^\s{2}\S+\s+\|\s+.*\|\s+pos=.*\|\s+lane=') { continue }
        $candidate = ConvertTo-WaiverCandidateView -Line $line
        if ($null -eq $candidate) { throw "BF-646 BLOCKED: unable to parse governed BF-616 shortlist line: $line" }
        $candidates += $candidate
    }
    return @($candidates)
}

function Get-WaiverAuthorizedCounts {
    param([Parameter(Mandatory = $true)][string]$Bundle)
    $raw = Get-LineValue -Text $Bundle -Label "Authorized shortlist total / historical / newcomer:"
    if ([string]::IsNullOrWhiteSpace($raw)) { throw "BF-646 BLOCKED: BF-617 authorized shortlist counts are missing" }
    $match = [regex]::Match($raw, '^(\d+)/(\d+)/(\d+)$')
    if (-not $match.Success) { throw "BF-646 BLOCKED: unable to parse BF-617 authorized shortlist counts: $raw" }
    return [pscustomobject]@{
        Total = [int]$match.Groups[1].Value
        Historical = [int]$match.Groups[2].Value
        Newcomer = [int]$match.Groups[3].Value
    }
}

function Get-WaiverLanePresentation {
    param([AllowNull()][string]$Lane)
    if ($Lane -match 'NEWCOMER') { return [pscustomobject]@{ Label="Newcomer review"; Class="newcomer" } }
    if ($Lane -match 'HISTORICAL') { return [pscustomobject]@{ Label="Historical directional"; Class="historical" } }
    return [pscustomobject]@{ Label=$Lane; Class="neutral" }
}

function Get-Bf623TargetView {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$BoundaryName
    )

    $gate = Get-LineValue -Text $Text -Label "Binding gate state:"
    $leagueRaw = Get-LineValue -Text $Text -Label "Bound Sleeper league:"
    $rosterRaw = Get-LineValue -Text $Text -Label "Bound roster / role:"
    $displayRaw = Get-LineValue -Text $Text -Label "Bound display/team:"

    if ($gate -cne "BOUND_TARGET_LIVE_VERIFIED") {
        throw "$BoundaryName BLOCKED: BF-623 binding gate is not BOUND_TARGET_LIVE_VERIFIED"
    }
    if ([string]::IsNullOrWhiteSpace($leagueRaw) -or [string]::IsNullOrWhiteSpace($rosterRaw) -or [string]::IsNullOrWhiteSpace($displayRaw)) {
        throw "$BoundaryName BLOCKED: BF-623 verified identity is missing"
    }

    $leagueMatch = [regex]::Match($leagueRaw, '^(?<id>[0-9]+)\s+\|\s+(?<name>.+)$')
    $rosterMatch = [regex]::Match($rosterRaw, '^(?<id>[0-9]+)\s+/\s+(?<role>[A-Z_]+)$')
    $displayMatch = [regex]::Match($displayRaw, '^(?<display>.*?)\s+/\s+(?<team>.*)$')
    if (-not $leagueMatch.Success -or -not $rosterMatch.Success -or -not $displayMatch.Success) {
        throw "$BoundaryName BLOCKED: unable to parse BF-623 verified identity"
    }

    $sleeperLeagueId = $leagueMatch.Groups['id'].Value.Trim()
    $leagueName = $leagueMatch.Groups['name'].Value.Trim()
    $rosterId = $rosterMatch.Groups['id'].Value.Trim()
    $role = $rosterMatch.Groups['role'].Value.Trim()
    $displayName = $displayMatch.Groups['display'].Value.Trim()
    $teamName = $displayMatch.Groups['team'].Value.Trim()

    if ($role -cne "OWNER") {
        throw "$BoundaryName BLOCKED: BF-623 target role is not exact OWNER"
    }
    if ([string]::IsNullOrWhiteSpace($leagueName) -or $leagueName -ceq "none") {
        throw "$BoundaryName BLOCKED: BF-623 verified league name is unavailable"
    }

    $identityName = $teamName
    if ([string]::IsNullOrWhiteSpace($identityName) -or $identityName -ceq "none") {
        $identityName = $displayName
    }
    if ([string]::IsNullOrWhiteSpace($identityName) -or $identityName -ceq "none") {
        throw "$BoundaryName BLOCKED: BF-623 verified team/display identity is unavailable"
    }

    return [pscustomobject]@{
        Human = "$leagueName | $identityName | roster $rosterId"
        SleeperLeagueId = $sleeperLeagueId
        RosterId = $rosterId
        LeagueName = $leagueName
        DisplayName = $displayName
        TeamName = $teamName
        IdentityName = $identityName
        Role = $role
        Gate = $gate
    }
}

function Get-WaiverTargetView {
    param([Parameter(Mandatory = $true)][string]$Bundle)

    $verified = Get-Bf623TargetView -Text $Bundle -BoundaryName "BF-648"
    $comparisonRaw = Get-LineValue -Text $Bundle -Label "Sleeper league / target roster:"
    if ([string]::IsNullOrWhiteSpace($comparisonRaw)) {
        throw "BF-648 BLOCKED: raw comparison target is missing"
    }
    $comparisonMatch = [regex]::Match($comparisonRaw, '^(?<league>[0-9]+)\s+/\s+(?<roster>[0-9]+)$')
    if (-not $comparisonMatch.Success) {
        throw "BF-648 BLOCKED: unable to parse raw comparison target"
    }
    $comparisonLeagueId = $comparisonMatch.Groups['league'].Value.Trim()
    $comparisonRosterId = $comparisonMatch.Groups['roster'].Value.Trim()
    if ($verified.SleeperLeagueId -cne $comparisonLeagueId -or $verified.RosterId -cne $comparisonRosterId) {
        throw "BF-648 BLOCKED: BF-623 verified league/roster identity disagrees with BF-616 raw comparison target"
    }

    return [pscustomobject]@{
        Human = $verified.Human
        SleeperLeagueId = $verified.SleeperLeagueId
        RosterId = $verified.RosterId
        LeagueName = $verified.LeagueName
        DisplayName = $verified.DisplayName
        TeamName = $verified.TeamName
        IdentityName = $verified.IdentityName
        Role = $verified.Role
        Gate = $verified.Gate
        RawComparison = $comparisonRaw
    }
}

function ConvertTo-SnapshotPairView {
    param(
        [AllowNull()][string]$Line,
        [Parameter(Mandatory = $true)][string]$BoundaryName
    )
    if ([string]::IsNullOrWhiteSpace($Line)) {
        throw "$BoundaryName BLOCKED: governed BF-603/BF-602 snapshot pair is missing"
    }
    $match = [regex]::Match($Line, '^(?<market>\S+)\s+/\s+(?<waiver>\S+)$')
    if (-not $match.Success) {
        throw "$BoundaryName BLOCKED: unable to parse governed BF-603/BF-602 snapshot pair"
    }
    $market = $match.Groups['market'].Value.Trim()
    $waiver = $match.Groups['waiver'].Value.Trim()
    if ($market -ceq "none" -or $waiver -ceq "none") {
        throw "$BoundaryName BLOCKED: governed BF-603/BF-602 snapshot identity is unavailable"
    }
    return [pscustomobject]@{ Market = $market; Waiver = $waiver; Raw = $Line }
}

function Get-CurrentGovernedAddView {
    param(
        [Parameter(Mandatory = $true)][string]$Bundle,
        [Parameter(Mandatory = $true)][string]$Summary
    )

    $bundleTarget = Get-WaiverTargetView -Bundle $Bundle
    $summaryTarget = Get-Bf623TargetView -Text $Summary -BoundaryName "BF-649"
    if ($bundleTarget.SleeperLeagueId -cne $summaryTarget.SleeperLeagueId) {
        throw "BF-649 BLOCKED: summary BF-623 target identity disagrees with waiver BF-623 target identity"
    }
    if ($bundleTarget.RosterId -cne $summaryTarget.RosterId) {
        throw "BF-649 BLOCKED: summary BF-623 target identity disagrees with waiver BF-623 target identity"
    }
    if ($bundleTarget.LeagueName -cne $summaryTarget.LeagueName) {
        throw "BF-649 BLOCKED: summary BF-623 target identity disagrees with waiver BF-623 target identity"
    }
    if ($bundleTarget.DisplayName -cne $summaryTarget.DisplayName) {
        throw "BF-649 BLOCKED: summary BF-623 target identity disagrees with waiver BF-623 target identity"
    }
    if ($bundleTarget.TeamName -cne $summaryTarget.TeamName) {
        throw "BF-649 BLOCKED: summary BF-623 target identity disagrees with waiver BF-623 target identity"
    }
    if ($bundleTarget.Role -cne $summaryTarget.Role) {
        throw "BF-649 BLOCKED: summary BF-623 target identity disagrees with waiver BF-623 target identity"
    }

    $state = Get-LineValue -Text $Summary -Label "Decision status:"
    if ([string]::IsNullOrWhiteSpace($state)) {
        throw "BF-649 BLOCKED: current governed decision state is missing"
    }
    $audit = ConvertTo-AuditView (Get-LineValue -Text $Summary -Label "Audit:")
    $bf629 = Get-LineValue -Text $Summary -Label "BF-629 live actionability:"
    $bf631 = Get-LineValue -Text $Summary -Label "BF-631 evidence lineage:"
    $auditedLineageRaw = Get-LineValue -Text $Summary -Label "BF-631 audited BF-603 / BF-602 snapshot:"

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
            MarketSnapshotId = "none"
            WaiverSnapshotId = "none"
            AuditedLineageRaw = $auditedLineageRaw
            BundleLineageRaw = Get-LineValue -Text $Bundle -Label "BF-603 market / BF-602 waiver snapshot:"
            Target = $bundleTarget
        }
    }

    if ($bf629 -cne "LIVE_ACTIONABLE_VERIFIED") {
        throw "BF-649 BLOCKED: current decision state does not preserve BF-629 LIVE_ACTIONABLE_VERIFIED"
    }
    if ($bf631 -cne "LATEST_EVIDENCE_LINEAGE_VERIFIED") {
        throw "BF-649 BLOCKED: current decision state does not preserve BF-631 LATEST_EVIDENCE_LINEAGE_VERIFIED"
    }
    if ([string]::IsNullOrWhiteSpace($audit.Id) -or $audit.Id -ceq "none") {
        throw "BF-649 BLOCKED: current audited recommendation identity is missing"
    }

    $auditedLineage = ConvertTo-SnapshotPairView -Line $auditedLineageRaw -BoundaryName "BF-649"
    $bundleLineageRaw = Get-LineValue -Text $Bundle -Label "BF-603 market / BF-602 waiver snapshot:"
    $bundleLineage = ConvertTo-SnapshotPairView -Line $bundleLineageRaw -BoundaryName "BF-649"
    if ($auditedLineage.Market -cne $bundleLineage.Market -or $auditedLineage.Waiver -cne $bundleLineage.Waiver) {
        throw "BF-649 BLOCKED: audited BF-603/BF-602 lineage disagrees with BF-616 comparison bundle"
    }

    $add = ConvertTo-PlayerView (Get-LineValue -Text $Summary -Label "ADD:")
    if ([string]::IsNullOrWhiteSpace($add.SleeperId) -or $add.SleeperId -notmatch '^[0-9]+$') {
        throw "BF-649 BLOCKED: current audited ADD exact Sleeper id is missing or malformed"
    }

    $candidates = @(Get-WaiverCandidates -Bundle $Bundle)
    $matches = @($candidates | Where-Object { $_.SleeperId -ceq $add.SleeperId })
    if ($matches.Count -ne 1) {
        throw "BF-649 BLOCKED: current audited ADD Sleeper id $($add.SleeperId) must resolve exactly once in BF-616 shortlist; found $($matches.Count)"
    }

    return [pscustomobject]@{
        Active = $true
        SleeperId = $add.SleeperId
        State = $state
        AuditId = $audit.Id
        AuditCaptured = $audit.Captured
        Bf629 = $bf629
        Bf631 = $bf631
        MarketSnapshotId = $auditedLineage.Market
        WaiverSnapshotId = $auditedLineage.Waiver
        AuditedLineageRaw = $auditedLineage.Raw
        BundleLineageRaw = $bundleLineage.Raw
        Target = $bundleTarget
    }
}


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

function Resolve-WaiverCandidateById {
    param(
        [Parameter(Mandatory = $true)][string]$Bundle,
        [Parameter(Mandatory = $true)][string]$SleeperId
    )
    if ($SleeperId -notmatch '^[0-9]+$') { return $null }
    $candidates = @(Get-WaiverCandidates -Bundle $Bundle)
    $counts = Get-WaiverAuthorizedCounts -Bundle $Bundle
    if ($candidates.Count -ne $counts.Total) {
        throw "BF-647 BLOCKED: parsed BF-616 shortlist count $($candidates.Count) does not match BF-617 authorized total $($counts.Total)"
    }
    $matches = @($candidates | Where-Object { $_.SleeperId -ceq $SleeperId })
    if ($matches.Count -gt 1) {
        throw "BF-647 BLOCKED: duplicate exact Sleeper id $SleeperId appeared in the governed BF-616 shortlist"
    }
    if ($matches.Count -eq 0) { return $null }
    return $matches[0]
}

function Get-SharedCss {
    return @'
:root{font-family:Inter,Segoe UI,Arial,sans-serif;color:#f7f8fb;background:#0b1020;line-height:1.45}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at top,#172447 0,#0b1020 38%,#070b15 100%);min-height:100vh}.shell{max-width:1120px;margin:0 auto;padding:28px 22px 56px}
.top{display:flex;justify-content:space-between;gap:20px;align-items:flex-end;margin-bottom:16px}.brand h1{font-size:38px;letter-spacing:.16em;margin:0}.brand p{margin:5px 0 0;color:#9ca9c8}.target{font-size:14px;color:#cbd4eb;text-align:right}
.nav{display:flex;gap:8px;margin:0 0 20px;flex-wrap:wrap}.nav a{color:#b9c6e5;text-decoration:none;padding:9px 13px;border:1px solid #28365f;border-radius:10px;background:#0d1630;font-weight:700}.nav a.active{background:#315dca;color:white;border-color:#315dca}
.panel{background:rgba(16,24,48,.88);border:1px solid #28365f;border-radius:20px;padding:22px;box-shadow:0 20px 60px rgba(0,0,0,.28);margin-bottom:18px}.statusrow{display:flex;align-items:flex-start;justify-content:space-between;gap:18px}.eyebrow{font-size:12px;text-transform:uppercase;letter-spacing:.14em;color:#8ea0c7}.status{font-weight:800;padding:9px 13px;border-radius:999px;font-size:13px;white-space:nowrap}.good{background:#123d2c;color:#8ff0b9}.done{background:#1c315c;color:#a9c6ff}.warn{background:#4b3713;color:#ffd98b}.danger{background:#4c2028;color:#ffb0bc}
.headline{font-size:28px;margin:6px 0 4px}.lede{color:#cbd4eb;margin:0;max-width:800px}.moves{display:grid;grid-template-columns:1fr 1fr;gap:18px;margin-top:20px}.move{border:1px solid #33436f;border-radius:16px;padding:20px;background:#0d1630}.move.add{border-color:#286a4c}.move.drop{border-color:#74414b}.move h2{font-size:12px;letter-spacing:.14em;margin:0 0 10px}.player-name{font-size:25px;font-weight:800;line-height:1.15}.player-meta{margin-top:8px;color:#aebada;font-size:14px}.player-id{margin-top:5px;color:#7485aa;font-size:12px}
.next{margin-top:18px;padding:18px;border-radius:16px;background:#0a142c;border:1px solid #2e467e}.next strong{display:block;font-size:16px;margin-bottom:5px}.next p{margin:0;color:#cbd4eb}.verify-grid,.fresh-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.fresh-grid{margin-top:12px}.verify,.fresh{padding:18px;border-radius:14px;background:#0d1630;border:1px solid #26345c}.check{font-weight:800;color:#8ff0b9}.alert{font-weight:800;color:#ffd98b}.verify small{display:block;color:#8797bd;margin-top:5px}.fresh strong{display:block;color:#9eabd0;font-size:13px}.fresh .age{font-size:22px;font-weight:800;margin-top:5px}.fresh .limit{font-size:12px;color:#8797bd;margin-top:4px}
.lineage{display:flex;justify-content:space-between;gap:20px;align-items:center}.lineage-copy strong,.lineage-copy span{display:block}.lineage-copy strong{font-size:17px}.lineage-copy span{color:#97a7ca;font-size:13px;margin-top:3px}.actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.button{display:inline-block;text-decoration:none;color:#fff;background:#315dca;padding:11px 16px;border-radius:11px;font-weight:700}.subtle{color:#94a2c5;font-size:13px}.boundary{font-size:13px;color:#a9b5d2}.lock{font-weight:800;color:#a9c6ff}
details{margin-top:14px;border-top:1px solid #28365f;padding-top:14px}summary{cursor:pointer;color:#a9b7d7;font-weight:700}.tech{margin-top:12px;display:grid;grid-template-columns:1fr 1fr;gap:8px 18px;font-family:Consolas,monospace;font-size:12px;color:#9eabd0}.tech div{word-break:break-word}.raw-guard{margin-top:12px;padding:12px;border-left:3px solid #536996;background:#0b142b;color:#bfc9e1;font-size:12px}
.position-section{margin-top:20px}.position-head{display:flex;justify-content:space-between;align-items:end;margin-bottom:10px}.position-head h2{margin:0;font-size:21px}.position-count{color:#8797bd;font-size:13px}.roster-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.roster-card{padding:16px;border:1px solid #2b3962;border-radius:14px;background:#0d1630}.roster-card .name{font-size:18px;font-weight:800}.roster-card .meta{color:#aebada;font-size:13px;margin-top:4px}.roster-card .slot{display:inline-block;margin-top:10px;padding:5px 8px;border-radius:999px;background:#17254a;color:#a9c6ff;font-size:11px;font-weight:800}.roster-card .id{color:#6f81aa;font-size:11px;margin-top:8px}.roster-card.current-governed{border-color:#416fda;box-shadow:0 0 0 1px rgba(65,111,218,.28)}.roster-card .current-marker{margin-top:10px}.roster-note{color:#9ba8c8;font-size:13px;margin-top:8px}
.board-note{margin-top:16px;padding:15px 17px;border:1px solid #6a5427;border-radius:14px;background:#261f10;color:#f0d79a}.not-rank{font-weight:900;letter-spacing:.08em}.board-stats{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-top:16px}.board-stat{padding:15px;border-radius:14px;background:#0d1630;border:1px solid #26345c}.board-stat strong{display:block;font-size:22px}.board-stat span{font-size:12px;color:#91a1c7}.board-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin-top:18px}.candidate-card{padding:18px;border:1px solid #2b3962;border-radius:15px;background:#0d1630}.candidate-card.current-governed{border-color:#416fda;box-shadow:0 0 0 1px rgba(65,111,218,.28)}.candidate-top{display:flex;justify-content:space-between;gap:12px;align-items:flex-start}.candidate-card .name{font-size:19px;font-weight:800}.candidate-card .meta{color:#aebada;font-size:13px;margin-top:4px}.candidate-badges{display:flex;gap:7px;align-items:center;justify-content:flex-end;flex-wrap:wrap}.current-marker{display:inline-block;padding:5px 8px;border-radius:999px;background:#203d79;color:#c7d9ff;font-size:11px;font-weight:900;white-space:nowrap}.current-copy{margin-top:10px;color:#a9c6ff;font-size:12px;font-weight:700}.current-context{margin-top:16px;padding:14px 16px;border:1px solid #416fda;border-radius:14px;background:#0d1b3a;color:#c7d9ff}.lane{display:inline-block;padding:5px 8px;border-radius:999px;font-size:11px;font-weight:800;white-space:nowrap}.lane.historical{background:#173a2b;color:#8ff0b9}.lane.newcomer{background:#3f3216;color:#ffd98b}.lane.neutral{background:#1c315c;color:#a9c6ff}.candidate-facts{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:14px}.candidate-facts div{font-size:12px;color:#aab7d6}.candidate-facts strong{display:block;color:#7487b5;font-size:10px;text-transform:uppercase;letter-spacing:.08em}.market{margin-top:12px;padding-top:11px;border-top:1px solid #26345c;color:#93a3c8;font-size:12px}.market strong{color:#cbd4eb}.board-disclaimer{font-size:12px;color:#8fa0c7;margin-top:12px}
@media(max-width:760px){.top{display:block}.target{text-align:left;margin-top:12px}.moves,.verify-grid,.fresh-grid,.tech,.roster-grid,.board-grid,.board-stats{grid-template-columns:1fr}.brand h1{font-size:30px}.statusrow{display:block}.status{display:inline-block;margin-top:12px}.lineage{display:block}.lineage .status{margin-top:10px}.candidate-top{display:block}.candidate-badges{justify-content:flex-start;margin-top:10px}.lane{margin-top:0}}
'@
}

function Get-HeaderHtml {
    param([AllowNull()][string]$Target, [Parameter(Mandatory=$true)][string]$Active)
    $dashboardClass = if ($Active -eq "dashboard") { "active" } else { "" }
    $teamClass = if ($Active -eq "team") { "active" } else { "" }
    $waiversClass = if ($Active -eq "waivers") { "active" } else { "" }
    return @"
<header class="top">
  <div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div>
  <div class="target">$(ConvertTo-HtmlText $Target)</div>
</header>
<nav class="nav" aria-label="Butler sections">
  <a class="$dashboardClass" href="/">Dashboard</a>
  <a class="$teamClass" href="/team">My Team</a>
  <a class="$waiversClass" href="/waivers">Waiver Board</a>
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
    $auditedLineage = Get-LineValue -Text $Summary -Label "BF-631 audited BF-603 / BF-602 snapshot:"
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
<section class="panel"><div class="eyebrow">Decision record</div><div class="lineage"><div class="lineage-copy"><strong>Immutable Butler audit captured</strong><span>Every governed recommendation remains traceable even after your roster changes.</span></div><div class="status done">AUDITED</div></div><details><summary>Technical details</summary><div class="tech"><div>Decision state: $(ConvertTo-HtmlText $state)</div><div>BF-629: $(ConvertTo-HtmlText $bf629)</div><div>BF-631: $(ConvertTo-HtmlText $bf631)</div><div>BF-631 audited BF-603 / BF-602: $(ConvertTo-HtmlText $auditedLineage)</div><div>BF-633: $(ConvertTo-HtmlText $bf633)</div><div>Audit ID: $(ConvertTo-HtmlText $audit.Id)</div><div>Captured UTC: $(ConvertTo-HtmlText $audit.Captured)</div><div>Telemetry UTC: $(ConvertTo-HtmlText $telemetry)</div><div>Warning threshold: $(ConvertTo-HtmlText $thresholdRaw) sec</div><div>BF-603 observed: $(ConvertTo-HtmlText $market.Observed)</div><div>BF-603 age: $(ConvertTo-HtmlText $market.Seconds) sec</div><div>BF-602 observed: $(ConvertTo-HtmlText $waiver.Observed)</div><div>BF-602 age: $(ConvertTo-HtmlText $waiver.Seconds) sec</div></div><div class="raw-guard">$(ConvertTo-HtmlText $guard)</div></details></section>
<section class="panel"><div class="actions"><a class="button" href="/">Refresh status</a><a class="button" href="/team">View My Team</a><a class="button" href="/waivers">View Waiver Board</a><span class="subtle">These views are read-only and do not start BF-641.</span></div></section>
<section class="panel boundary"><span class="lock">READ ONLY.</span> Butler does not refresh evidence, rerank players, capture an audit, set FAAB, submit a Sleeper transaction, cancel a transaction, or mutate your league from this dashboard.</section>
</main></body></html>
"@
}

function ConvertTo-TeamHtml {
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

function ConvertTo-WaiverHtml {
    param(
        [Parameter(Mandatory = $true)][string]$Bundle,
        [Parameter(Mandatory = $true)][string]$Summary
    )

    $candidates = @(Get-WaiverCandidates -Bundle $Bundle)
    $counts = Get-WaiverAuthorizedCounts -Bundle $Bundle
    if ($candidates.Count -ne $counts.Total) {
        throw "BF-646 BLOCKED: parsed BF-616 shortlist count $($candidates.Count) does not match BF-617 authorized total $($counts.Total)"
    }

    $current = Get-CurrentGovernedAddView -Bundle $Bundle -Summary $Summary
    $target = $current.Target
    $lineage = Get-LineValue -Text $Bundle -Label "BF-603 market / BF-602 waiver snapshot:"
    $methodology = Get-LineValue -Text $Bundle -Label "BF-614 methodology:"
    $bf615 = Get-LineValue -Text $Bundle -Label "BF-615 state:"
    $bf616 = Get-LineValue -Text $Bundle -Label "BF-616 state:"
    $bf617 = Get-LineValue -Text $Bundle -Label "BF-617 state:"
    if ([string]::IsNullOrWhiteSpace($lineage) -or [string]::IsNullOrWhiteSpace($bf615) -or [string]::IsNullOrWhiteSpace($bf616) -or [string]::IsNullOrWhiteSpace($bf617)) {
        throw "BF-646 BLOCKED: governed BF-615/BF-616/BF-617 lineage or state is missing"
    }

    $css = Get-SharedCss
    $header = Get-HeaderHtml -Target $target.Human -Active "waivers"
    $cards = ""

    foreach ($candidate in $candidates) {
        $lane = Get-WaiverLanePresentation -Lane $candidate.Lane
        $injuryText = if ($candidate.Injury -eq "none") { "None reported" } else { $candidate.Injury }
        $depthText = if ($candidate.Depth -eq "none/none") { "Not available" } else { $candidate.Depth }
        $isCurrent = $current.Active -and $candidate.SleeperId -ceq $current.SleeperId
        $cardClass = if ($isCurrent) { "current-governed" } else { "" }
        $currentBadge = if ($isCurrent) { '<span class="current-marker">Current governed ADD</span>' } else { "" }
        $currentCopy = if ($isCurrent) { '<div class="current-copy">Already-audited current ADD &middot; this marker is not a board rank.</div>' } else { "" }
        $cards += @"
<article class="candidate-card $cardClass">
  <div class="candidate-top">
    <div><div class="name">$(ConvertTo-HtmlText $candidate.Name)</div><div class="meta">$(ConvertTo-HtmlText $candidate.Position) &middot; NFL $(ConvertTo-HtmlText $candidate.Team) &middot; Sleeper $(ConvertTo-HtmlText $candidate.SleeperId)</div>$currentCopy</div>
    <div class="candidate-badges">$currentBadge<span class="lane $($lane.Class)">$(ConvertTo-HtmlText $lane.Label)</span></div>
  </div>
  <div class="candidate-facts">
    <div><strong>Status</strong>$(ConvertTo-HtmlText $candidate.Status)</div>
    <div><strong>Injury</strong>$(ConvertTo-HtmlText $injuryText)</div>
    <div><strong>Depth</strong>$(ConvertTo-HtmlText $depthText)</div>
    <div><strong>BF-616 lane</strong>$(ConvertTo-HtmlText $candidate.Lane)</div>
  </div>
  <div class="market"><strong>Market attention:</strong> add $(ConvertTo-HtmlText $candidate.MarketAdd) / drop $(ConvertTo-HtmlText $candidate.MarketDrop) / net $(ConvertTo-HtmlText $candidate.MarketNet)</div>
  <div class="actions" style="margin-top:12px"><a class="button" href="/waivers/candidate/$(ConvertTo-HtmlText $candidate.SleeperId)">View governed details</a></div>
</article>
"@
    }

    if ($candidates.Count -eq 0) {
        $cards = '<div class="subtle">BF-616 has no authorized shortlist entries in the current governed frame.</div>'
    }

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Waiver Board</title><style>$css</style></head><body><main class="shell">
$header
<section class="panel">
  <div class="eyebrow">Governed Waiver Board</div>
  <h1 class="headline">Candidates Butler authorized for review</h1>
  <p class="lede">This page renders the exact BF-616 shortlist from Butler's frozen comparison methodology. It does not select a new winner or change the current audited recommendation.</p>
  <div class="board-note"><span class="not-rank">NOT A RANKING.</span> Cards remain in BF-616 deterministic display order. Top-to-bottom placement is not preference, value, priority, or advice.</div>
  <div class="board-stats">
    <div class="board-stat"><strong>$($counts.Total)</strong><span>Authorized shortlist</span></div>
    <div class="board-stat"><strong>$($counts.Historical)</strong><span>Historical directional lane</span></div>
    <div class="board-stat"><strong>$($counts.Newcomer)</strong><span>Newcomer review lane &middot; nonnumeric</span></div>
  </div>
  <div class="board-grid">$cards</div>
  <div class="board-disclaimer">Status, injury, depth, and market attention are descriptive only. Market attention is descriptive only and is not Butler's score. Newcomers remain nonnumeric. If shown, <strong>Current governed ADD</strong> identifies the already-audited current add only; it does not alter BF-616 order or rank the board.</div>
  <details><summary>Technical details</summary><div class="tech"><div>BF-623 target: $(ConvertTo-HtmlText $target.Human)</div><div>Raw Sleeper league / roster: $(ConvertTo-HtmlText $target.SleeperLeagueId) / $(ConvertTo-HtmlText $target.RosterId)</div><div>Raw comparison identity: $(ConvertTo-HtmlText $target.RawComparison)</div><div>BF-623 target gate: $(ConvertTo-HtmlText $target.Gate)</div><div>BF-623 role: $(ConvertTo-HtmlText $target.Role)</div><div>Current decision state: $(ConvertTo-HtmlText $current.State)</div><div>Current audit ID: $(ConvertTo-HtmlText $current.AuditId)</div><div>Current ADD Sleeper ID: $(ConvertTo-HtmlText $current.SleeperId)</div><div>BF-629 current gate: $(ConvertTo-HtmlText $current.Bf629)</div><div>BF-631 current gate: $(ConvertTo-HtmlText $current.Bf631)</div><div>Audited BF-603 / BF-602: $(ConvertTo-HtmlText $current.AuditedLineageRaw)</div><div>Bundle BF-603 / BF-602: $(ConvertTo-HtmlText $current.BundleLineageRaw)</div><div>BF-603 / BF-602: $(ConvertTo-HtmlText $lineage)</div><div>BF-614 methodology: $(ConvertTo-HtmlText $methodology)</div><div>BF-615: $(ConvertTo-HtmlText $bf615)</div><div>BF-616: $(ConvertTo-HtmlText $bf616)</div><div>BF-617: $(ConvertTo-HtmlText $bf617)</div><div>Parsed shortlist: $($candidates.Count)</div></div></details>
</section>
<section class="panel boundary"><span class="lock">READ ONLY &middot; NOT A RANKING.</span> Waiver Board shows the governed BF-616 shortlist and, when exact BF-623/BF-631/BF-603/BF-602 reconciliation passes, identifies Butler's already-audited current ADD. It does not rerank candidates, weight market/depth/injury, score newcomers, pick a new winner, identify a new drop, set FAAB, run BF-641, refresh evidence, or submit a Sleeper transaction.</section>
</main></body></html>
"@
}

function ConvertTo-WaiverCandidateDetailHtml {
    param(
        [Parameter(Mandatory = $true)][string]$Bundle,
        [Parameter(Mandatory = $true)]$Candidate,
        [Parameter(Mandatory = $true)][string]$Summary
    )
    $current = Get-CurrentGovernedAddView -Bundle $Bundle -Summary $Summary
    $target = $current.Target
    $lineage = Get-LineValue -Text $Bundle -Label "BF-603 market / BF-602 waiver snapshot:"
    $methodology = Get-LineValue -Text $Bundle -Label "BF-614 methodology:"
    $bf616 = Get-LineValue -Text $Bundle -Label "BF-616 state:"
    $bf617 = Get-LineValue -Text $Bundle -Label "BF-617 state:"
    if ([string]::IsNullOrWhiteSpace($lineage) -or [string]::IsNullOrWhiteSpace($bf616) -or [string]::IsNullOrWhiteSpace($bf617)) {
        throw "BF-647 BLOCKED: governed BF-616/BF-617 lineage or state is missing"
    }

    $lane = Get-WaiverLanePresentation -Lane $Candidate.Lane
    $injuryText = if ($Candidate.Injury -eq "none") { "None reported" } else { $Candidate.Injury }
    $depthText = if ($Candidate.Depth -eq "none/none") { "Not available" } else { $Candidate.Depth }
    $laneCopy = if ($Candidate.Lane -match 'NEWCOMER') {
        "Newcomer review remains explicitly nonnumeric. Butler does not fabricate a production score or rank this player against historical candidates."
    } else {
        "Historical directional traceability comes only from Butler's frozen governed comparison method. Comparator sets below are evidence lineage, not a new score or ranking."
    }
    $isCurrent = $current.Active -and $Candidate.SleeperId -ceq $current.SleeperId
    $currentBadge = if ($isCurrent) { '<span class="current-marker">Current governed ADD</span>' } else { "" }
    $currentContext = if ($isCurrent) {
        '<div class="current-context"><strong>Current governed ADD.</strong> This exact Sleeper ID is the already-audited ADD from Butler&apos;s current governed recommendation. The marker is traceability, not a new score or board ranking.</div>'
    } else { "" }
    $css = Get-SharedCss
    $header = Get-HeaderHtml -Target $target.Human -Active "waivers"

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Waiver Candidate</title><style>$css</style></head><body><main class="shell">
$header
<section class="panel">
  <div class="eyebrow">Governed candidate detail</div>
  <div class="statusrow"><div><h1 class="headline">$(ConvertTo-HtmlText $Candidate.Name)</h1><p class="lede">$(ConvertTo-HtmlText $Candidate.Position) &middot; NFL $(ConvertTo-HtmlText $Candidate.Team) &middot; Sleeper $(ConvertTo-HtmlText $Candidate.SleeperId)</p></div><div class="candidate-badges">$currentBadge<span class="lane $($lane.Class)">$(ConvertTo-HtmlText $lane.Label)</span></div></div>
  <div class="board-note"><span class="not-rank">NOT A RANKING.</span> This page inspects one exact BF-616 authorized candidate. It does not change Butler's current recommendation.</div>
  $currentContext
  <div class="candidate-facts">
    <div><strong>Status</strong>$(ConvertTo-HtmlText $Candidate.Status)</div>
    <div><strong>Injury</strong>$(ConvertTo-HtmlText $injuryText)</div>
    <div><strong>Depth</strong>$(ConvertTo-HtmlText $depthText)</div>
    <div><strong>BF-616 lane</strong>$(ConvertTo-HtmlText $Candidate.Lane)</div>
  </div>
  <div class="market"><strong>Market attention:</strong> add $(ConvertTo-HtmlText $Candidate.MarketAdd) / drop $(ConvertTo-HtmlText $Candidate.MarketDrop) / net $(ConvertTo-HtmlText $Candidate.MarketNet)</div>
  <div class="next"><strong>Governed interpretation</strong><p>$(ConvertTo-HtmlText $laneCopy)</p></div>
  <details open><summary>Comparator traceability</summary><div class="tech"><div>Candidate-supported comparators: $(ConvertTo-HtmlText $Candidate.SupportedComparators)</div><div>Eligible comparators: $(ConvertTo-HtmlText $Candidate.EligibleComparators)</div><div>BF-623 target: $(ConvertTo-HtmlText $target.Human)</div><div>Raw Sleeper league / roster: $(ConvertTo-HtmlText $target.SleeperLeagueId) / $(ConvertTo-HtmlText $target.RosterId)</div><div>Raw comparison identity: $(ConvertTo-HtmlText $target.RawComparison)</div><div>BF-623 target gate: $(ConvertTo-HtmlText $target.Gate)</div><div>BF-623 role: $(ConvertTo-HtmlText $target.Role)</div><div>Current decision state: $(ConvertTo-HtmlText $current.State)</div><div>Current audit ID: $(ConvertTo-HtmlText $current.AuditId)</div><div>Current ADD Sleeper ID: $(ConvertTo-HtmlText $current.SleeperId)</div><div>BF-629 current gate: $(ConvertTo-HtmlText $current.Bf629)</div><div>BF-631 current gate: $(ConvertTo-HtmlText $current.Bf631)</div><div>Audited BF-603 / BF-602: $(ConvertTo-HtmlText $current.AuditedLineageRaw)</div><div>Bundle BF-603 / BF-602: $(ConvertTo-HtmlText $current.BundleLineageRaw)</div><div>BF-614 methodology: $(ConvertTo-HtmlText $methodology)</div><div>BF-603 / BF-602: $(ConvertTo-HtmlText $lineage)</div><div>BF-616: $(ConvertTo-HtmlText $bf616)</div><div>BF-617: $(ConvertTo-HtmlText $bf617)</div></div></details>
  <div class="actions" style="margin-top:18px"><a class="button" href="/waivers">Back to Waiver Board</a></div>
</section>
<section class="panel boundary"><span class="lock">READ ONLY &middot; EXACT ID ONLY.</span> Candidate detail resolves only from the current reconciled BF-616 shortlist by exact Sleeper id. A Current governed ADD marker, when present, comes only from the existing audited recommendation after exact target and evidence-lineage reconciliation. It does not use name lookup, rerank candidates, score newcomers, change the recommendation, run BF-641, refresh evidence, set FAAB, or submit a Sleeper transaction.</section>
</main></body></html>
"@
}

function Send-HttpResponse {
    param(
        [Parameter(Mandatory=$true)]$Stream,
        [Parameter(Mandatory=$true)][int]$StatusCode,
        [Parameter(Mandatory=$true)][string]$StatusText,
        [Parameter(Mandatory=$true)][string]$ContentType,
        [Parameter(Mandatory=$true)][string]$Body
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
    Write-Host "BF-643/BF-644/BF-645/BF-646/BF-647/BF-648/BF-649 Butler Dashboard"
    Write-Host "Local URL: $url"
    Write-Host "My Team: http://127.0.0.1:$Port/team"
    Write-Host "Waiver Board: http://127.0.0.1:$Port/waivers"
    Write-Host "Candidate detail: http://127.0.0.1:$Port/waivers/candidate/<exact-sleeper-id>"
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

            $candidateMatch = [regex]::Match($path, '^/waivers/candidate/(?<id>[0-9]+)$')
            $knownStaticPath = $path -eq "/" -or $path -eq "/index.html" -or $path -eq "/team" -or $path -eq "/waivers"
            if (-not $knownStaticPath -and -not $candidateMatch.Success) {
                Send-HttpResponse -Stream $stream -StatusCode 404 -StatusText "Not Found" -ContentType "text/plain; charset=utf-8" -Body "Not found"
                continue
            }

            try {
                if ($path -eq "/team") {
                    $summary = Invoke-ButlerReadOnlySummary
                    $rosterContext = Invoke-ButlerReadOnlyRosterContext
                    $html = ConvertTo-TeamHtml -RosterContext $rosterContext -Summary $summary
                }
                elseif ($path -eq "/waivers") {
                    $summary = Invoke-ButlerReadOnlySummary
                    $waiverBundle = Invoke-ButlerReadOnlyWaiverBoard
                    $html = ConvertTo-WaiverHtml -Bundle $waiverBundle -Summary $summary
                }
                elseif ($candidateMatch.Success) {
                    $candidateId = $candidateMatch.Groups['id'].Value
                    $summary = Invoke-ButlerReadOnlySummary
                    $waiverBundle = Invoke-ButlerReadOnlyWaiverBoard
                    $candidate = Resolve-WaiverCandidateById -Bundle $waiverBundle -SleeperId $candidateId
                    if ($null -eq $candidate) {
                        $notFoundHtml = "<!doctype html><html><body><h1>Candidate not authorized</h1><p>Candidate is not in the current BF-616 authorized shortlist.</p><p><a href=`"/waivers`">Back to Waiver Board</a></p></body></html>"
                        Send-HttpResponse -Stream $stream -StatusCode 404 -StatusText "Not Found" -ContentType "text/html; charset=utf-8" -Body $notFoundHtml
                        continue
                    }
                    $html = ConvertTo-WaiverCandidateDetailHtml -Bundle $waiverBundle -Candidate $candidate -Summary $summary
                }
                else {
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
        finally {
            $client.Close()
        }
    }
}
finally {
    $listener.Stop()
    Pop-Location
}
