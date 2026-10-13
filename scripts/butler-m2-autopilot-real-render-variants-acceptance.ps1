Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# BF-M2: exercise the actual Auto-Pilot HTML renderer using a real, staged
# production CSS provider and independent synthetic current/held status
# shapes. No HTTP, files from user data, Sleeper requests, DB or transactions.
# No dot-sourcing files with entry point side effects: parse AST and use only
# verified production function definitions in this isolated process.
$worker = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
$hostPath = Join-Path $PSScriptRoot 'butler-trade-lab-host.ps1'
foreach ($spec in @(
    @{ Path = $hostPath; Name = 'Get-AppCss' },
    @{ Path = $worker; Name = 'Get-V04AutoPilotApprovalPolicy' },
    @{ Path = $worker; Name = 'Get-V04AutoPilotApprovalQueue' },
    @{ Path = $worker; Name = 'Get-V04AutoPilotCss' },
    @{ Path = $worker; Name = 'Get-V04AutoPilotHtml' }
)) {
    $tokens = $null
    $parseErrors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile(
        $spec.Path, [ref]$tokens, [ref]$parseErrors)
    if (@($parseErrors).Count -ne 0) { throw 'BF-M2 BLOCKED: renderer source parse error.' }
    $name = [string]$spec.Name
    $found = @($ast.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -ceq $name
    }, $true))
    if ($found.Count -ne 1) { throw 'BF-M2 BLOCKED: missing production function.' }
    . ([scriptblock]::Create($found[0].Extent.Text))
}

$policy = Get-V04AutoPilotApprovalPolicy
$base = @{
    Ready = $false
    Attention = 'UNAVAILABLE'
    StartSit = 'UNAVAILABLE'
    Waivers = 'UNAVAILABLE'
    Roster = 'Fixture team'
    EvidenceStatus = 'UNVERIFIED'
}
$cases = @(
    @{ Title = 'unverified'; Changes = @{}; Hold = 'WATCH DATA UNAVAILABLE'; Week = 'UNVERIFIED' },
    @{ Title = 'stale'; Changes = @{
        Attention = 'NEEDS REFRESH'; StartSit = 'REFRESH'; Waivers = 'DO NOT ACT'
        EvidenceStatus = 'STALE'
    }; Hold = 'EVIDENCE NEEDS REFRESH'; Week = 'UNVERIFIED' },
    @{ Title = 'week-mismatch'; Changes = @{
        Attention = 'SYNC SAVED WEEK'; StartSit = 'DO NOT ACT'
        Waivers = 'DO NOT ACT'; EvidenceStatus = 'WEEK_MISMATCH'
    }; Hold = 'SAVED WEEK OUTDATED'; Week = 'UNVERIFIED' },
    @{ Title = 'dashboard-ready-but-lineup-held'; Changes = @{
        Ready = $true; Attention = '2 NEED ATTENTION'
        StartSit = 'REFRESH'; Waivers = 'MOVE READY'; EvidenceStatus = 'CURRENT'
    }; Hold = 'LINEUP ACTION HELD'; Week = 'UNVERIFIED' },
    @{ Title = 'verified-change'; Changes = @{
        Ready = $true; Attention = '1 NEED ATTENTION'; StartSit = 'START 1 / SIT 1'
        Waivers = 'DO NOT ACT'; EvidenceStatus = 'CURRENT'
        StartSitReviewOutcome = 'VERIFIED_CHANGE'
        StartSitStatusFetchedAtUtc = '2026-10-13T06:00:00Z'
        StartSitProjectionFetchedAtUtc = '2026-10-13T06:00:00Z'
        StartSitVerifiedChangeCount = 1
        VerifiedWeekSeason = '2026'; VerifiedWeekNumber = 6
    }; Hold = 'CURRENT SNAPSHOT'; Week = 'MATCH' },
    @{ Title = 'verified-no-change'; Changes = @{
        Ready = $true; Attention = '0 NEED ATTENTION'; StartSit = 'NO CHANGE VERIFIED'
        Waivers = 'DO NOT ACT'; EvidenceStatus = 'CURRENT'
        StartSitReviewOutcome = 'VERIFIED_NO_CHANGE'
        StartSitProjectionFetchedAtUtc = '2026-10-13T06:00:00Z'
        VerifiedWeekSeason = '2026'; VerifiedWeekNumber = 6
    }; Hold = 'CURRENT SNAPSHOT'; Week = 'MATCH' }
)

foreach ($fixture in $cases) {
    $fields = @{}
    foreach ($key in $base.Keys) { $fields[$key] = $base[$key] }
    foreach ($key in $fixture.Changes.Keys) { $fields[$key] = $fixture.Changes[$key] }
    $watch = [pscustomobject]$fields
    $queue = Get-V04AutoPilotApprovalQueue -WatchState $watch -ApprovalPolicy $policy
    $script:ButlerAutoPilotRenderSubstage = 'UNSET'
    $html = Get-V04AutoPilotHtml -WatchState $watch -ApprovalPolicy $policy -ApprovalQueue $queue
    if ([regex]::Matches($html, '<!doctype html>', 'IgnoreCase').Count -ne 1 -or
        [regex]::Matches($html, '</body>', 'IgnoreCase').Count -ne 1 -or
        $html.IndexOf(('data-butler-watch-week-state="' + $fixture.Week + '"'),
            [StringComparison]::Ordinal) -lt 0 -or
        $html.IndexOf([string]$fixture.Hold, [StringComparison]::Ordinal) -lt 0 -or
        [string]$script:ButlerAutoPilotRenderSubstage -cne 'RENDER_HTML') {
        throw ('BF-M2 BLOCKED: production renderer does not support {0} synthetic state.' -f $fixture.Title)
    }
    if (-not [bool]$watch.Ready -and
        $html.IndexOf('CURRENT SNAPSHOT', [StringComparison]::Ordinal) -ge 0) {
        throw 'BF-M2 BLOCKED: blocked source incorrectly displayed CURRENT SNAPSHOT.'
    }
    if (-not ($watch.PSObject.Properties['StartSitReviewOutcome']) -and
        $html.IndexOf('data-butler-lineup-proof="held"', [StringComparison]::Ordinal) -lt 0) {
        throw 'BF-M2 BLOCKED: unreviewed Start/Sit must remain held.'
    }
}
Write-Host 'BF-M2 AUTO-PILOT PRODUCTION RENDER VARIANTS: PASS (6 source states, actual CSS, no network).'
