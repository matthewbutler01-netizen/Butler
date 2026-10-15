param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# BF-1518: Dashboard's saved AutoFill metadata has a week, timestamp, and
# team/roster binding. Unlike BF-1005 Matchup, this view does not compare
# saved starter slots to a live BF-610 roster fingerprint. Those data alone
# cannot verify a still-current lineup after a manager edits Sleeper.
if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw 'BF-1518 BLOCKED: final staged Dashboard not found.'
}

$source = [IO.File]::ReadAllText($DashboardPath)
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors)
if (@($errors).Count -gt 0) {
    throw 'BF-1518 BLOCKED: staged Dashboard has PowerShell parse errors.'
}
$matches = @($ast.FindAll({
    param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -ceq 'ConvertTo-DashboardHtml'
},$true))
if ($matches.Count -ne 1) {
    throw 'BF-1518 BLOCKED: expected exactly one Dashboard renderer.'
}
$fn = $matches[0]
$body = $fn.Extent.Text
$anchor = '    $lineupAttentionGroup = if (-not [string]::IsNullOrWhiteSpace($lineupSnapshotAttentionGroup)) {'
$first = $body.IndexOf($anchor,[StringComparison]::Ordinal)
if ($first -lt 0 -or $body.IndexOf($anchor,$first + $anchor.Length,[StringComparison]::Ordinal) -ge 0) {
    throw 'BF-1518 BLOCKED: final Dashboard lineup decision anchor is not unique.'
}
$gate = @'
    # BF-1518: a saved review is not a live-lineup proof. The public Dashboard
    # has no independent current BF-610 starter-slot fingerprint to compare.
    # Matchup/Start-Sit owns that check. Never silently upgrade a saved result
    # to READY/NO CHANGES merely because it was saved in the last six hours.
    if ($null -ne $lineupSnapshot -and
        $snapshotFresh -and $snapshotTargetMatches -and
        [bool]$verification.RosterOk -and [bool]$lineupSnapshot.Ready) {
        $lineupSignalTitle = 'Saved lineup review - verify current starters'
        $lineupSignalCopy = 'A read-only Week ' + [string]$lineupSnapshot.Week +
            ' AutoFill review was saved for this team. Dashboard has not compared its starter slots ' +
            'with the current Sleeper lineup. Open Start/Sit Assistant for a current read-only ' +
            'review before acting; no lineup was submitted.'
        $lineupSignalStatus = 'RECHECK LINEUP'
        $lineupSignalClass = 'warn'
        $lineupSnapshotAttentionGroup = 'attention'
    }

'@
$body = $body.Insert($first,$gate)
$source = $source.Substring(0,$fn.Extent.StartOffset) +
    $body + $source.Substring($fn.Extent.EndOffset)

$tokens = $null
$errors = $null
[void][Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors)
if (@($errors).Count -gt 0) {
    throw 'BF-1518 BLOCKED: final Dashboard proof gate failed PowerShell parse.'
}
foreach ($required in @(
    'BF-1518: a saved review is not a live-lineup proof.',
    "'RECHECK LINEUP'",
    'Open Start/Sit Assistant for a current read-only',
    'no lineup was submitted.',
    '$snapshotFresh -and $snapshotTargetMatches'
)) {
    if ($source.IndexOf($required,[StringComparison]::Ordinal) -lt 0) {
        throw "BF-1518 BLOCKED: final Dashboard gate marker missing: $required"
    }
}

[IO.File]::WriteAllText($DashboardPath,$source,[Text.UTF8Encoding]::new($false))
Write-Host 'BF-1518 Dashboard saved-lineup trust gate applied.'
