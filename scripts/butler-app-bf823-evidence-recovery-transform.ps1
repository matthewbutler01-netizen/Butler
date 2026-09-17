param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-823 BLOCKED: staged Butler core not found at $CorePath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $first = $Text.IndexOf($Old, [System.StringComparison]::Ordinal)
    if ($first -lt 0) {
        throw "BF-823 BLOCKED: $Contract anchor is missing."
    }
    $second = $Text.IndexOf($Old, $first + $Old.Length, [System.StringComparison]::Ordinal)
    if ($second -ge 0) {
        throw "BF-823 BLOCKED: $Contract anchor is ambiguous."
    }
    return $Text.Substring(0, $first) + $New + $Text.Substring($first + $Old.Length)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$gapOld = @'
        $reason = [string]$AutoFill.Reason
        $projectionGap = $reason.IndexOf('projection', [System.StringComparison]::OrdinalIgnoreCase) -ge 0
        if ($projectionGap) {
'@
$gapNew = @'
        $reason = [string]$AutoFill.Reason
        $recoveryGap = $reason -match 'BF-598|BF-610|roster membership drifted|current roster player identity'
        $projectionGap = $reason.IndexOf('projection', [System.StringComparison]::OrdinalIgnoreCase) -ge 0
        $retryHref = '/team/autofill'
        if ($recoveryGap) {
            $gapTitle = 'Butler data needs refresh'
            $gapLede = 'Your Sleeper roster or Butler saved roster evidence changed since the last lineup review.'
            $decisionCopy = 'No lineup recommendation until Butler refreshes the local roster evidence used by AutoFill.'
            $whyCopy = 'Butler detected roster or player identity evidence that no longer matches the current league state.'
            $retryLabel = 'Refresh Butler Data'
            $retryHref = '/refresh'
            $gapStatus = 'DATA REFRESH NEEDED'
        }
        elseif ($projectionGap) {
'@
$core = Replace-ExactlyOnce -Text $core -Old $gapOld -New $gapNew -Contract 'Lineup Advisor recovery-gap classification'

$ctaOld = '<a class=`"btn btn-primary`" href=`"/team/autofill`">$(ConvertTo-HtmlText $retryLabel)</a>'
$ctaNew = '<a class=`"btn btn-primary`" href=`"$(ConvertTo-HtmlText $retryHref)`">$(ConvertTo-HtmlText $retryLabel)</a>'
$core = Replace-ExactlyOnce -Text $core -Old $ctaOld -New $ctaNew -Contract 'Lineup Advisor evidence-gap recovery action'

$errorCatchOld = @'
                catch {
                    $errorHtml = "<!doctype html><html><body><h1>Butler AutoFill view blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No Butler or Sleeper write was executed.</p><p><a href=`"/team`">Back to My Team</a></p></body></html>"
                    Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText "Internal Server Error" -ContentType "text/html; charset=utf-8" -Body $errorHtml
                }
'@
$errorCatchNew = @'
                catch {
                    $reasonText = [string]$_.Exception.Message
                    $recoveryGap = $reasonText -match 'BF-598|BF-610|roster membership drifted|current roster player identity'
                    $css = Get-AppCss
                    $nav = Get-AppNav -Active "team"
                    if ($recoveryGap) {
                        $errorTitle = 'Butler data needs refresh'
                        $errorLede = 'Your Sleeper roster or Butler saved roster evidence changed since the last lineup review.'
                        $errorDecision = 'Refresh Butler data before asking for another lineup recommendation.'
                        $errorWhy = 'Butler stopped rather than build a lineup from roster evidence that no longer matches the current league state.'
                        $errorStatus = 'DATA REFRESH NEEDED'
                        $actionHref = '/refresh'
                        $actionLabel = 'Refresh Butler Data'
                    }
                    else {
                        $errorTitle = 'Lineup review is temporarily blocked'
                        $errorLede = 'Butler could not complete this read-only lineup review.'
                        $errorDecision = 'No lineup recommendation is available from this attempt.'
                        $errorWhy = 'A required governed evidence check stopped before Butler could prove a complete weekly lineup.'
                        $errorStatus = 'REVIEW BLOCKED'
                        $actionHref = '/team/autofill'
                        $actionLabel = 'Retry Lineup Review'
                    }
                    $safeReason = ConvertTo-HtmlText $reasonText
                    $safeTitle = ConvertTo-HtmlText $errorTitle
                    $safeLede = ConvertTo-HtmlText $errorLede
                    $safeDecision = ConvertTo-HtmlText $errorDecision
                    $safeWhy = ConvertTo-HtmlText $errorWhy
                    $safeStatus = ConvertTo-HtmlText $errorStatus
                    $safeActionHref = ConvertTo-HtmlText $actionHref
                    $safeActionLabel = ConvertTo-HtmlText $actionLabel
                    $errorHtml = @"
<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Butler - $safeTitle</title><style>$css</style></head>
<body><main class="shell"><div class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div></div>$nav
<section class="panel recommendation-panel"><div class="manager-head"><div><div class="eyebrow">Lineup advisor</div><h2>$safeTitle</h2><p class="lede">$safeLede</p></div><span class="status warn">$safeStatus</span></div><div class="manager-summary"><div class="summary-card"><h3>Decision</h3><p>$safeDecision</p></div><div class="summary-card"><h3>Why</h3><p>$safeWhy</p></div></div><details><summary>View evidence details</summary><div class="callout callout-danger">$safeReason</div></details><div class="button-row"><a class="btn btn-primary" href="$safeActionHref">$safeActionLabel</a><a class="btn btn-secondary" href="/team">Back to My Team</a></div><p class="meta"><strong>Read only:</strong> this blocked lineup review did not submit a lineup or any Sleeper transaction.</p></section>
</main></body></html>
"@
                    Send-HttpResponse -Stream $stream -StatusCode 500 -StatusText "Internal Server Error" -ContentType "text/html; charset=utf-8" -Body $errorHtml
                }
'@
$core = Replace-ExactlyOnce -Text $core -Old $errorCatchOld -New $errorCatchNew -Contract 'raw AutoFill blocked page'

if ($core -notmatch 'Butler data needs refresh') {
    throw 'BF-823 BLOCKED: manager-facing roster evidence recovery copy is missing.'
}
if ($core -notmatch 'DATA REFRESH NEEDED') {
    throw 'BF-823 BLOCKED: roster evidence recovery status is missing.'
}
if ($core -notmatch 'Refresh Butler Data') {
    throw 'BF-823 BLOCKED: explicit Butler data refresh action is missing.'
}
if ($core -match '<h1>Butler AutoFill view blocked</h1>') {
    throw 'BF-823 BLOCKED: legacy raw AutoFill failure page remains in staged core.'
}
if ($core -notmatch 'View evidence details') {
    throw 'BF-823 BLOCKED: technical evidence disclosure regressed.'
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))
