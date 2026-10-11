param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-976 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $matches = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($matches -ne 1) {
        throw "BF-976 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$playerSearchRouteCount = [regex]::Matches(
    $core,
    [regex]::Escape('            if ($path -eq "/players") {')
).Count
if ($playerSearchRouteCount -ne 1) {
    throw "BF-976 BLOCKED: expected exactly one Player Search route before final safe-stop override, found $playerSearchRouteCount."
}

foreach ($required in @(
    'function New-ManagerRecoveryPageHtml',
    'New-ManagerRecoveryPageHtml -Title "Player Detail unavailable"',
    'New-ManagerRecoveryPageHtml -Title "Player Search unavailable"',
    'New-ManagerRecoveryPageHtml -Title "Player Compare unavailable"'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-976 BLOCKED: BF-884 finalized recovery marker is missing: $required"
    }
}

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-976 BLOCKED: staged core failed pre-transform parse: $summary"
}

$helperMarker = 'function Get-PlayerCompareRequest {'
$helperIndex = $core.IndexOf($helperMarker, [System.StringComparison]::Ordinal)
if ($helperIndex -lt 0) {
    throw 'BF-976 BLOCKED: Player Compare helper insertion marker is missing.'
}

$helper = @'
function Get-PlayerWorkflowBlockedHtml {
    param(
        [Parameter(Mandatory = $true)][string]$Title,
        [Parameter(Mandatory = $true)][string]$Message,
        [Parameter(Mandatory = $true)][string]$Active,
        [Parameter(Mandatory = $true)][string]$PrimaryHref,
        [Parameter(Mandatory = $true)][string]$PrimaryLabel,
        [string]$SecondaryHref = '/',
        [string]$SecondaryLabel = 'Dashboard'
    )

    $css = Get-AppCss
    $nav = Get-AppNav -Active $Active
    $safeTitle = ConvertTo-HtmlText $Title
    # BF-1121: never reflect exception or player/league-identifying text
    # into a public blocked page. Only fixed internal reason tokens render.
    $publicMessage = switch -CaseSensitive ($Message) {
        'PLAYER_DETAIL_UNVERIFIED' { 'Butler could not verify this player detail. Return to Player Search and check the latest source evidence.' }
        'PLAYER_SEARCH_UNVERIFIED' { 'Butler could not complete this player search. Try Player Search again when local evidence is available.' }
        'PLAYER_COMPARE_UNVERIFIED' { 'Butler could not verify this comparison. Return to Player Compare and review the selected players.' }
        default { 'Butler could not verify this page. Do not act on incomplete information.' }
    }
    $safeMessage = ConvertTo-HtmlText $publicMessage
    $safePrimaryHref = ConvertTo-HtmlText $PrimaryHref
    $safePrimaryLabel = ConvertTo-HtmlText $PrimaryLabel
    $safeSecondaryHref = ConvertTo-HtmlText $SecondaryHref
    $safeSecondaryLabel = ConvertTo-HtmlText $SecondaryLabel

    return @"
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="dark">
<title>Butler - $safeTitle</title>
<style>$css
.player-blocked-actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-top:18px}.player-blocked-actions a{display:inline-block;border:1px solid var(--line);border-radius:10px;padding:10px 14px;text-decoration:none;font-weight:700}.player-blocked-detail{margin-top:16px;white-space:pre-wrap;overflow-wrap:anywhere}
</style>
</head>
<body>
<main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">Player workflow stopped safely</div></header>
$nav
<section class="panel">
<div class="eyebrow">Safe stop</div>
<div class="statusrow"><div><h1 class="headline">$safeTitle</h1><p class="lede">Butler stopped this request instead of guessing or continuing with an invalid player context.</p></div><span class="status warn">STOPPED SAFELY</span></div>
<pre class="player-blocked-detail">$safeMessage</pre>
<p>No Sleeper transaction was submitted by this page. An earlier guarded Butler-local evidence recovery may have run before the failure.</p>
<div class="player-blocked-actions"><a href="$safePrimaryHref">$safePrimaryLabel</a><a href="$safeSecondaryHref">$safeSecondaryLabel</a></div>
</section>
</main>
</body>
</html>
"@
}

'@

$core = $core.Insert($helperIndex, $helper)

$detailOld = '$errorHtml = New-ManagerRecoveryPageHtml -Title "Player Detail unavailable" -Status "STOPPED SAFELY" -Summary "Butler could not verify this player detail safely, so it stopped instead of guessing." -Detail "UNVERIFIED_SOURCE" -Active "team"'
$detailNew = '$errorHtml = Get-PlayerWorkflowBlockedHtml -Title ''Player Detail blocked'' -Message ''PLAYER_DETAIL_UNVERIFIED'' -Active ''players'' -PrimaryHref ''/players'' -PrimaryLabel ''Back to Player Search'' -SecondaryHref ''/team'' -SecondaryLabel ''My Team'''
$core = Replace-ExactlyOnce -Text $core -Old $detailOld -New $detailNew -Contract 'BF-884 Player Detail recovery override'

$searchOld = '$errorHtml = New-ManagerRecoveryPageHtml -Title "Player Search unavailable" -Status "STOPPED SAFELY" -Summary "Butler could not complete that player search safely. Adjust the search or return to another manager view." -Detail "UNVERIFIED_SOURCE" -Active "league"'
$searchNew = '$errorHtml = Get-PlayerWorkflowBlockedHtml -Title ''Player Search blocked'' -Message ''PLAYER_SEARCH_UNVERIFIED'' -Active ''players'' -PrimaryHref ''/players'' -PrimaryLabel ''Back to Player Search'''
$core = Replace-ExactlyOnce -Text $core -Old $searchOld -New $searchNew -Contract 'BF-884 Player Search recovery override'

$compareOld = '$errorHtml = New-ManagerRecoveryPageHtml -Title "Player Compare unavailable" -Status "STOPPED SAFELY" -Summary "Butler could not verify that player comparison safely, so it stopped instead of guessing." -Detail "UNVERIFIED_SOURCE" -Active "league"'
$compareNew = '$errorHtml = Get-PlayerWorkflowBlockedHtml -Title ''Player Compare blocked'' -Message ''PLAYER_COMPARE_UNVERIFIED'' -Active ''compare'' -PrimaryHref ''/compare'' -PrimaryLabel ''Back to Player Compare'' -SecondaryHref ''/players'' -SecondaryLabel ''Player Search'''
$core = Replace-ExactlyOnce -Text $core -Old $compareOld -New $compareNew -Contract 'BF-884 Player Compare recovery override'

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseInput($core, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-976 BLOCKED: generated staged core failed PowerShell parse: $summary"
}

$helperDefinitionCount = [regex]::Matches(
    $core,
    '(?m)^function Get-PlayerWorkflowBlockedHtml\s*\{'
).Count
if ($helperDefinitionCount -ne 1) {
    throw "BF-976 BLOCKED: expected exactly one installed Player workflow blocked renderer, found $helperDefinitionCount."
}

foreach ($required in @(
    "Get-PlayerWorkflowBlockedHtml -Title 'Player Detail blocked'",
    "Get-PlayerWorkflowBlockedHtml -Title 'Player Search blocked'",
    "Get-PlayerWorkflowBlockedHtml -Title 'Player Compare blocked'",
    'STOPPED SAFELY',
    'No Sleeper transaction was submitted by this page.'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-976 BLOCKED: installed player-workflow safe-stop marker is missing: $required"
    }
}

foreach ($superseded in @(
    'New-ManagerRecoveryPageHtml -Title "Player Detail unavailable"',
    'New-ManagerRecoveryPageHtml -Title "Player Search unavailable"',
    'New-ManagerRecoveryPageHtml -Title "Player Compare unavailable"'
)) {
    if ($core.IndexOf($superseded, [System.StringComparison]::Ordinal) -ge 0) {
        throw "BF-976 BLOCKED: superseded BF-884 player recovery call remains: $superseded"
    }
}

$finalPlayerSearchRouteCount = [regex]::Matches(
    $core,
    [regex]::Escape('            if ($path -eq "/players") {')
).Count
if ($finalPlayerSearchRouteCount -ne 1) {
    throw "BF-976 BLOCKED: Player Search route count changed during final safe-stop override; found $finalPlayerSearchRouteCount."
}

if ($helper -match 'Invoke-RestMethod|Invoke-WebRequest|Invoke-ButlerReadOnly|Invoke-Bf742DashboardWorkerRead|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-976 BLOCKED: Player workflow safe-stop renderer introduced provider, backend-read, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-976 Player workflow safe-stop pages applied after BF-884.'
