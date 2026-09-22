param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CorePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CorePath -PathType Leaf)) {
    throw "BF-884 BLOCKED: staged Butler core not found at $CorePath"
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
        throw "BF-884 BLOCKED: $Contract expected one match, found $matches."
    }
    return $Text.Replace($Old, $New)
}

$core = [System.IO.File]::ReadAllText($CorePath)

$helper = @'
function New-ManagerRecoveryPageHtml {
    param(
        [Parameter(Mandatory = $true)][string]$Title,
        [Parameter(Mandatory = $true)][string]$Status,
        [Parameter(Mandatory = $true)][string]$Summary,
        [AllowEmptyString()][string]$Detail = '',
        [ValidateSet('dashboard','team','matchup','waivers','league','trade','history')]
        [string]$Active = 'dashboard'
    )

    $css = Get-AppCss
    $nav = Get-AppNav -Active $Active
    $detailsHtml = ''
    if (-not [string]::IsNullOrWhiteSpace($Detail)) {
        $detailsHtml = "<details><summary>Technical details</summary><div class=`"technical`">$(ConvertTo-HtmlText $Detail)</div></details>"
    }

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - $(ConvertTo-HtmlText $Title)</title><style>$css</style></head><body><main class="shell">
<header class="top"><div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div><div class="target">Recovery</div></header>
$nav
<section class="panel hero-panel"><div class="manager-head"><div><div class="eyebrow">Butler recovery</div><h1 class="headline">$(ConvertTo-HtmlText $Title)</h1><p class="lede">$(ConvertTo-HtmlText $Summary)</p></div><span class="status warn">$(ConvertTo-HtmlText $Status)</span></div><div class="button-row"><a class="btn btn-primary" href="/">Dashboard</a><a class="btn btn-secondary" href="/team">My Team</a><a class="btn btn-secondary" href="/league">League</a></div>$detailsHtml</section>
<section class="panel boundary"><span class="lock">SAFE RECOVERY.</span> These actions only navigate inside Butler. This page does not refresh evidence, rerun a recommendation, submit a lineup, or execute a Sleeper transaction.</section>
</main></body></html>
"@
}

'@

$marker = 'function ConvertTo-LeagueHtml {'
$markerIndex = $core.IndexOf($marker, [System.StringComparison]::Ordinal)
if ($markerIndex -lt 0) {
    throw 'BF-884 BLOCKED: recovery helper insertion marker is missing.'
}
$core = $core.Insert($markerIndex, $helper)

$replacements = @(
    [pscustomobject]@{
        Contract = 'League blocked page'
        Old = '$errorHtml = "<!doctype html><html><body><h1>Butler League view blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No Butler or Sleeper write was executed.</p></body></html>"'
        New = '$errorHtml = New-ManagerRecoveryPageHtml -Title "League view unavailable" -Status "STOPPED SAFELY" -Summary "Butler stopped the League view rather than continue with incomplete or unsafe evidence." -Detail $_.Exception.Message -Active "league"'
    },
    [pscustomobject]@{
        Contract = 'My Team blocked page'
        Old = '$errorHtml = "<!doctype html><html><body><h1>Butler My Team view blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No Butler or Sleeper write was executed.</p></body></html>"'
        New = '$errorHtml = New-ManagerRecoveryPageHtml -Title "My Team unavailable" -Status "STOPPED SAFELY" -Summary "Butler stopped My Team rather than continue with incomplete or unsafe evidence." -Detail $_.Exception.Message -Active "team"'
    },
    [pscustomobject]@{
        Contract = 'generic app blocked page'
        Old = '$errorHtml = "<!doctype html><html><body><h1>Butler app blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No Butler or Sleeper write was executed.</p></body></html>"'
        New = '$errorHtml = New-ManagerRecoveryPageHtml -Title "Butler could not complete this view" -Status "STOPPED SAFELY" -Summary "Butler stopped rather than continue with incomplete or unsafe evidence." -Detail $_.Exception.Message -Active "dashboard"'
    },
    [pscustomobject]@{
        Contract = 'Player Detail blocked page'
        Old = '$errorHtml = "<!doctype html><html><body><h1>Butler Player Detail blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No provider refresh, Butler write, or Sleeper write was executed.</p></body></html>"'
        New = '$playerDetailFailure = [string]$_; if ([string]::IsNullOrWhiteSpace($playerDetailFailure) -and $null -ne $_.Exception) { $playerDetailFailure = [string]$_.Exception }; if ([string]::IsNullOrWhiteSpace($playerDetailFailure)) { $playerDetailFailure = "Player Detail failed without diagnostic text." }; $errorHtml = New-ManagerRecoveryPageHtml -Title "Player Detail unavailable" -Status "STOPPED SAFELY" -Summary "Butler could not verify this player detail safely, so it stopped instead of guessing." -Detail $playerDetailFailure -Active "team"'
    },
    [pscustomobject]@{
        Contract = 'Franchise Detail blocked page'
        Old = '$errorHtml = "<!doctype html><html><body><h1>Butler Franchise Detail blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No provider refresh, Butler write, or Sleeper write was executed.</p></body></html>"'
        New = '$errorHtml = New-ManagerRecoveryPageHtml -Title "Franchise Detail unavailable" -Status "STOPPED SAFELY" -Summary "Butler could not verify this franchise detail safely, so it stopped instead of guessing." -Detail $_.Exception.Message -Active "league"'
    },
    [pscustomobject]@{
        Contract = 'Player Search blocked page'
        Old = '$errorHtml = "<!doctype html><html><body><h1>Butler Player Search blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No provider refresh, Butler write, or Sleeper write was executed.</p><p><a href=`"/players`">Back to Player Search</a></p></body></html>"'
        New = '$errorHtml = New-ManagerRecoveryPageHtml -Title "Player Search unavailable" -Status "STOPPED SAFELY" -Summary "Butler could not complete that player search safely. Adjust the search or return to another manager view." -Detail $_.Exception.Message -Active "league"'
    },
    [pscustomobject]@{
        Contract = 'Player Compare blocked page'
        Old = '$errorHtml = "<!doctype html><html><body><h1>Butler Player Compare blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No provider refresh, Butler write, or Sleeper write was executed.</p><p><a href=`"/players`">Back to Player Search</a></p></body></html>"'
        New = '$errorHtml = New-ManagerRecoveryPageHtml -Title "Player Compare unavailable" -Status "STOPPED SAFELY" -Summary "Butler could not verify that player comparison safely, so it stopped instead of guessing." -Detail $_.Exception.Message -Active "league"'
    },
    [pscustomobject]@{
        Contract = 'Weekly Matchup blocked page'
        Old = '$errorHtml = "<!doctype html><html><body><h1>Butler Weekly Matchup view blocked</h1><pre>$(ConvertTo-HtmlText $_.Exception.Message)</pre><p>No Butler or Sleeper write was executed.</p></body></html>"'
        New = '$errorHtml = New-ManagerRecoveryPageHtml -Title "Weekly Matchup unavailable" -Status "STOPPED SAFELY" -Summary "Butler could not verify the matchup view safely, so it stopped instead of guessing." -Detail $_.Exception.Message -Active "matchup"'
    }
)

foreach ($replacement in $replacements) {
    $core = Replace-ExactlyOnce -Text $core -Old $replacement.Old -New $replacement.New -Contract $replacement.Contract
}

$notFoundOld = '                Send-HttpResponse -Stream $stream -StatusCode 404 -StatusText "Not Found" -ContentType "text/plain; charset=utf-8" -Body "Not found"'
$notFoundNew = @'
                $notFoundHtml = New-ManagerRecoveryPageHtml -Title "Page not found" -Status "NOT FOUND" -Summary "That Butler page is not available. Use a manager view below to keep going." -Detail "" -Active "dashboard"
                Send-HttpResponse -Stream $stream -StatusCode 404 -StatusText "Not Found" -ContentType "text/html; charset=utf-8" -Body $notFoundHtml
'@
$core = Replace-ExactlyOnce -Text $core -Old $notFoundOld -New $notFoundNew.TrimEnd() -Contract 'unknown-route 404 page'

foreach ($required in @(
    'function New-ManagerRecoveryPageHtml',
    'Butler recovery',
    'Technical details',
    'SAFE RECOVERY.',
    'STOPPED SAFELY',
    'Page not found',
    '-StatusCode 404 -StatusText "Not Found" -ContentType "text/html; charset=utf-8"',
    'href="/">Dashboard</a>',
    'href="/team">My Team</a>',
    'href="/league">League</a>'
)) {
    if ($core.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-884 BLOCKED: required manager-recovery marker is missing: $required"
    }
}

$installedStart = $core.IndexOf('function New-ManagerRecoveryPageHtml', [System.StringComparison]::Ordinal)
$installedEnd = $core.IndexOf('function ConvertTo-LeagueHtml {', $installedStart, [System.StringComparison]::Ordinal)
$installed = $core.Substring($installedStart, $installedEnd - $installedStart)
foreach ($forbidden in @(
    'Invoke-RestMethod',
    'Invoke-WebRequest',
    'Method = "POST"',
    'https://api.sleeper.app',
    'submitTransaction',
    'setFaab',
    'Start-Process'
)) {
    if ($installed.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "BF-884 BLOCKED: recovery UI introduced forbidden retry, provider, write, or refresh behavior: $forbidden"
    }
}

[System.IO.File]::WriteAllText($CorePath, $core, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($CorePath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-884 BLOCKED: generated staged core failed PowerShell parse: $parseSummary"
}
