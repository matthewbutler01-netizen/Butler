Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf979-weekly-attention-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-979 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf979-weekly-attention-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $corePath = Join-Path $root 'butler-app-shell-core-single.ps1'
    $dashboardPath = Join-Path $root 'butler-dashboard.ps1'

    $coreTokens = $null
    $coreErrors = $null
    $coreAst = [System.Management.Automation.Language.Parser]::ParseFile($corePath, [ref]$coreTokens, [ref]$coreErrors)
    if (@($coreErrors).Count -ne 0) {
        $summary = (@($coreErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-979 BLOCKED: staged core has parse errors: $summary"
    }

    $dashboardTokens = $null
    $dashboardErrors = $null
    $dashboardAst = [System.Management.Automation.Language.Parser]::ParseFile($dashboardPath, [ref]$dashboardTokens, [ref]$dashboardErrors)
    if (@($dashboardErrors).Count -ne 0) {
        $summary = (@($dashboardErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-979 BLOCKED: staged Dashboard has parse errors: $summary"
    }

    function Get-OneFunction {
        param(
            [Parameter(Mandatory = $true)]$Ast,
            [Parameter(Mandatory = $true)][string]$Name
        )
        $matches = @($Ast.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $Name
        }, $true))
        if ($matches.Count -ne 1) {
            throw "BF-979 BLOCKED: expected one $Name function, found $($matches.Count)."
        }
        return $matches[0]
    }

    $save = (Get-OneFunction -Ast $coreAst -Name 'Save-Bf808AutoFillSnapshot').Extent.Text
    foreach ($required in @(
        "Schema = 'BF-808-1'",
        'AvailabilityExclusions = @($availabilitySnapshot)',
        'ProjectionHolds = @($holdSnapshot)',
        "RosterSlot = [string]$_.RosterSlot",
        "LineupSlot = [string]$_.LineupSlot",
        "InjuryStatus = [string]$_.InjuryStatus"
    )) {
        if ($save.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-979 BLOCKED: snapshot attention marker is missing: $required"
        }
    }

    $reader = (Get-OneFunction -Ast $dashboardAst -Name 'Read-Bf809AutoFillSnapshotFile').Extent.Text
    if ($reader.IndexOf("'AvailabilityExclusions'", [System.StringComparison]::Ordinal) -ge 0 -or
        $reader.IndexOf("'ProjectionHolds'", [System.StringComparison]::Ordinal) -ge 0) {
        throw 'BF-979 BLOCKED: optional Weekly Attention fields became mandatory for legacy snapshots.'
    }
    if ($reader.IndexOf("'BF-808-1'", [System.StringComparison]::Ordinal) -lt 0) {
        throw 'BF-979 BLOCKED: BF-808 snapshot compatibility schema changed.'
    }

    $team = (Get-OneFunction -Ast $coreAst -Name 'ConvertTo-TeamHtml').Extent.Text
    foreach ($required in @(
        'Get-Bf979WeeklyAttentionHtml -AutoFill $AutoFill',
        '$autoFillHtml = $weeklyAttentionHtml + $autoFillHtml',
        'id="team-lineup"'
    )) {
        if ($team.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-979 BLOCKED: My Team Weekly Attention composition marker is missing: $required"
        }
    }

    $dashboard = (Get-OneFunction -Ast $dashboardAst -Name 'ConvertTo-DashboardHtml').Extent.Text
    foreach ($required in @(
        'Get-Bf979SnapshotWeeklyAttentionHtml -Snapshot $lineupSnapshot',
        '$bf979HasWeeklyAttention = -not [string]::IsNullOrWhiteSpace($bf979WeeklyAttentionHtml)',
        '"LINEUP NEEDS ATTENTION"',
        '$bf907AttentionClass = if ($bf979HasWeeklyAttention -or $managerAttentionCount -gt 0)',
        '$bf907WeekGlanceHtml = $bf979WeeklyAttentionHtml + $bf907WeekGlanceHtml'
    )) {
        if ($dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-979 BLOCKED: Dashboard Weekly Attention composition marker is missing: $required"
        }
    }

    function ConvertTo-HtmlText {
        param([AllowNull()]$Value)
        return [System.Net.WebUtility]::HtmlEncode([string]$Value)
    }

    $liveRenderer = (Get-OneFunction -Ast $coreAst -Name 'Get-Bf979WeeklyAttentionHtml').Extent.Text
    Invoke-Expression $liveRenderer

    $autoFill = [pscustomobject]@{
        Requested = $true
        Ready = $true
        AvailabilityExclusions = @(
            [pscustomobject]@{
                Name = 'Unavailable <One>'
                Id = '101'
                Status = 'Inactive'
                InjuryStatus = 'Out'
                Reason = 'Unavailable for weekly review.'
            }
        )
        ProjectionHolds = @(
            [pscustomobject]@{
                Name = 'Starter Hold'
                Id = '102'
                RosterSlot = 'STARTER'
                LineupSlot = 'WR'
                Status = 'Active'
                InjuryStatus = 'Questionable'
                Reason = 'Availability hold: review before promotion.'
            },
            [pscustomobject]@{
                Name = 'Bench Hold'
                Id = '103'
                RosterSlot = 'BENCH'
                LineupSlot = 'none'
                Status = 'Active'
                InjuryStatus = 'none'
                Reason = 'Usage review hold.'
            }
        )
    }

    $liveHtml = Get-Bf979WeeklyAttentionHtml -AutoFill $autoFill
    foreach ($required in @(
        'Weekly attention',
        '1 starter needs weekly review',
        'Unavailable &lt;One&gt;',
        'Starter Hold',
        'Questionable',
        'href="/team/autofill">Review Lineup</a>',
        'Butler does not invent replacement points'
    )) {
        if ($liveHtml.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-979 BLOCKED: live Weekly Attention render marker is missing: $required"
        }
    }

    $clearAutoFill = [pscustomobject]@{
        Requested = $true
        Ready = $true
        AvailabilityExclusions = @()
        ProjectionHolds = @()
    }
    if (-not [string]::IsNullOrWhiteSpace((Get-Bf979WeeklyAttentionHtml -AutoFill $clearAutoFill))) {
        throw 'BF-979 BLOCKED: live Weekly Attention rendered when no attention evidence exists.'
    }

    $cachedRenderer = (Get-OneFunction -Ast $dashboardAst -Name 'Get-Bf979SnapshotWeeklyAttentionHtml').Extent.Text
    Invoke-Expression $cachedRenderer

    $snapshot = [pscustomobject]@{
        Ready = $true
        AvailabilityExclusions = @($autoFill.AvailabilityExclusions)
        ProjectionHolds = @($autoFill.ProjectionHolds)
    }
    $cachedHtml = Get-Bf979SnapshotWeeklyAttentionHtml -Snapshot $snapshot -LineupSignalStatus 'AUTOFILL READY'
    foreach ($required in @(
        'Weekly attention',
        '1 starter needs weekly review',
        'Unavailable &lt;One&gt; (Out)',
        'Starter Hold (Questionable)',
        'Dashboard does not make a new provider request for this alert.',
        'href="/team/autofill">Review Lineup</a>'
    )) {
        if ($cachedHtml.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-979 BLOCKED: cached Dashboard Weekly Attention render marker is missing: $required"
        }
    }

    if (-not [string]::IsNullOrWhiteSpace((Get-Bf979SnapshotWeeklyAttentionHtml -Snapshot $snapshot -LineupSignalStatus 'REFRESH AUTOFILL'))) {
        throw 'BF-979 BLOCKED: Dashboard showed stale Weekly Attention when the saved lineup snapshot needs refresh.'
    }

    $surface = $liveRenderer + [Environment]::NewLine + $cachedRenderer
    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-979 BLOCKED: Weekly Attention renderers introduced provider, optimizer, FAAB, write, or open-redirect behavior.'
    }

    Write-Host 'BF-979 WEEKLY ATTENTION ACCEPTANCE: PASS'
    Write-Host 'Coverage: saved lineup evidence -> Dashboard alert; live Lineup Review -> My Team alert; no new provider read'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
