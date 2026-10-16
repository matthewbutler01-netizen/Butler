param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw 'BF-1521 BLOCKED: final Waiver Board source not found.'
}
$source = [IO.File]::ReadAllText($DashboardPath)
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors)
if (@($errors).Count -gt 0) {
    throw 'BF-1521 BLOCKED: existing Waiver Board source cannot parse.'
}
$functions = @($ast.FindAll({
    param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -ceq 'ConvertTo-WaiverHtml'
},$true))
if ($functions.Count -ne 1) {
    throw 'BF-1521 BLOCKED: expected exactly one Waiver Board renderer.'
}
$waiverFn = $functions[0]
$waiver = $waiverFn.Extent.Text
foreach ($expected in @(
    '$waiverDecisionTitle',
    '$waiverDecisionCopy',
    '$waiverDecisionStatus',
    '$waiverDecisionClass',
    '$waiverNextActionCopy',
    'NO_TRANSACTION_TO_ACT_ON',
    'NO_AUDITED_DECISION'
)) {
    if (-not $waiver.Contains($expected)) {
        throw "BF-1521 BLOCKED: governed Waiver Board field missing: $expected"
    }
}
$helpers = @'
function Get-Bf1521WaiverDecisionClarity {
    param([AllowNull()][string]$State, [int]$CandidateCount = 0)

    # Decision-state interpretation only. No new ranking or source action.
    switch -CaseSensitive ($State) {
        'NO_TRANSACTION_TO_ACT_ON' {
            $copy = if ($CandidateCount -le 0) {
                'This evidence frame produced no verified add/drop pair and no authorized review candidates. This does not prove that no useful free agents exist.'
            }
            else {
                'This evidence frame produced no verified add/drop pair. The authorized review pool is context, not a ranking or a new recommendation.'
            }
            return [pscustomobject]@{
                Title = 'No verified waiver move to recommend'
                Copy = $copy
                Status = 'NO VERIFIED MOVE'
                Class = 'warn'
                Next = 'Do not treat a saved no-move decision as a current-week free-agent search. A new recommendation requires an updated, governed evidence review. Existing automatic refresh runs only when the independent safety gate permits it.'
            }
        }
        'NO_AUDITED_DECISION' {
            return [pscustomobject]@{
                Title = 'No verified waiver decision available'
                Copy = 'Butler has not verified a current add/drop pair from this evidence. An empty recommendation is not proof that no worthwhile free agents are available.'
                Status = 'NOT VERIFIED'
                Class = 'warn'
                Next = 'Do not act on old add/drop advice. Review the authorized candidates as context, or wait for a new governed current-week evaluation before making a waiver decision.'
            }
        }
        default { return $null }
    }
}

function Get-Bf1521WaiverRefreshLink {
    param(
        [AllowNull()][string]$State,
        [AllowNull()][string]$Actionability,
        [AllowNull()][string]$Lineage
    )

    # Align exactly with BF-676 NO_TRANSACTION_TO_ACT_ON manual preflight.
    # A link is a confirmation-only capability: /refresh POST still
    # authorizes each Butler-local evidence write independently.
    if ($State -cne 'NO_TRANSACTION_TO_ACT_ON' -or
        $Actionability -cne 'NO_TRANSACTION_TO_REVALIDATE' -or
        @('LATEST_EVIDENCE_LINEAGE_VERIFIED',
          'MARKET_LINEAGE_SUPERSEDED',
          'WAIVER_LINEAGE_SUPERSEDED',
          'MARKET_AND_WAIVER_LINEAGE_SUPERSEDED') -cnotcontains $Lineage) {
        return ''
    }
    return '<a class="button" href="/refresh">Check latest waiver evidence (confirmation required)</a>'
}
'@
$helperAt = $waiverFn.Extent.StartOffset
if ($source.Contains('function Get-Bf1521WaiverDecisionClarity {')) {
    throw 'BF-1521 BLOCKED: helper already present; refusing duplicate installation.'
}
$source = $source.Insert($helperAt,$helpers + [Environment]::NewLine + [Environment]::NewLine)
# AST positions change after inserting the helper: parse again for exact renderer.
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors)
if (@($errors).Count -gt 0) { throw 'BF-1521 BLOCKED: helper injection broke parse.' }
$found = @($ast.FindAll({
    param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'ConvertTo-WaiverHtml'
},$true))
if ($found.Count -ne 1) { throw 'BF-1521 BLOCKED: Waiver renderer duplicate.' }
$waiverFn = $found[0]
$waiver = $waiverFn.Extent.Text
$returnAt = $waiver.LastIndexOf('    return @"',[StringComparison]::Ordinal)
if ($returnAt -lt 0) { throw 'BF-1521 BLOCKED: final Waiver Board return not found.' }
$clarifier = @'
    # BF-1521: a saved NO TRANSACTION decision is not a current-week
    # free-agent search. Do not invent rankings or authorize any add/drop.
    $bf1521Clarity = Get-Bf1521WaiverDecisionClarity -State ([string]$current.State) -CandidateCount ([int]$counts.Total)
    if ($null -ne $bf1521Clarity) {
        $waiverDecisionTitle = $bf1521Clarity.Title
        $waiverDecisionCopy = $bf1521Clarity.Copy
        $waiverDecisionStatus = $bf1521Clarity.Status
        $waiverDecisionClass = $bf1521Clarity.Class
        $waiverNextActionCopy = $bf1521Clarity.Next
    }
    $bf1521RefreshLink = Get-Bf1521WaiverRefreshLink -State ([string]$current.State) -Actionability ([string]$current.Bf629) -Lineage ([string]$current.Bf631)

'@
$waiver = $waiver.Insert($returnAt,$clarifier)
# Append a link only inside the already-existing BF-959 next-step surface.
# The link never carries an audit ID or grants POST authorization.
$from = '$waiverQuickActions$waiverHistoryLink'
$to = '$waiverQuickActions$bf1521RefreshLink$waiverHistoryLink'
if ([regex]::Matches($waiver,[regex]::Escape($from)).Count -ne 1) {
    throw 'BF-1521 BLOCKED: Waiver next-step insertion point changed.'
}
$waiver = $waiver.Replace($from,$to)
$source = $source.Substring(0,$waiverFn.Extent.StartOffset) +
    $waiver + $source.Substring($waiverFn.Extent.EndOffset)
$errors = $null
[void][Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors)
if (@($errors).Count -gt 0) { throw 'BF-1521 BLOCKED: final transformed Waiver Board failed parse.' }
foreach ($required in @('Get-Bf1521WaiverDecisionClarity','Get-Bf1521WaiverRefreshLink','bf1521RefreshLink','NO VERIFIED MOVE','NOT VERIFIED','current-week free-agent search')) {
    if (-not $source.Contains($required)) {
        throw "BF-1521 BLOCKED: visible decision-state contract missing: $required"
    }
}
[IO.File]::WriteAllText($DashboardPath,$source,[Text.UTF8Encoding]::new($false))
Write-Host 'BF-1521 verified waiver no-move clarity applied.'
