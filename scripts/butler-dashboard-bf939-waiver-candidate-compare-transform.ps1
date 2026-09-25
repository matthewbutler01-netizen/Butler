param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-939 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Contract
    )

    $count = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($count -ne 1) {
        throw "BF-939 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

$text = [System.IO.File]::ReadAllText($DashboardPath)

# Add the compare entry point to every rendered authorized candidate card.
$cardActionOld = '<div class="actions" style="margin-top:12px"><a class="button" href="/waivers/candidate/$(ConvertTo-HtmlText $candidate.SleeperId)">View governed details</a></div>'
$cardActionNew = '<div class="actions" style="margin-top:12px"><a class="button" href="/waivers/candidate/$(ConvertTo-HtmlText $candidate.SleeperId)">View governed details</a><a class="button" href="/waivers/compare?left=$(ConvertTo-HtmlText $candidate.SleeperId)">Compare candidate</a></div>'
$text = Replace-ExactlyOnce -Text $text -Old $cardActionOld -New $cardActionNew -Contract 'Waiver Board compare entry action'

$helperMarker = 'function Send-HttpResponse {'
$helperIndex = $text.IndexOf($helperMarker, [System.StringComparison]::Ordinal)
if ($helperIndex -lt 0) {
    throw 'BF-939 BLOCKED: dashboard response helper marker is missing.'
}

$compareFunctions = @'
function Get-WaiverCompareRequest {
    param([Parameter(Mandatory = $true)][string]$RequestTarget)

    $values = @{
        left = @()
        right = @()
    }

    $question = $RequestTarget.IndexOf('?')
    if ($question -ge 0 -and $question + 1 -lt $RequestTarget.Length) {
        foreach ($pair in @($RequestTarget.Substring($question + 1) -split '&')) {
            if ([string]::IsNullOrWhiteSpace($pair)) { continue }
            $equals = $pair.IndexOf('=')
            if ($equals -lt 0) { continue }

            $key = [System.Uri]::UnescapeDataString($pair.Substring(0, $equals).Replace('+', ' ')).Trim()
            if (-not $values.ContainsKey($key)) { continue }

            $value = [System.Uri]::UnescapeDataString($pair.Substring($equals + 1).Replace('+', ' ')).Trim()
            $values[$key] += $value
        }
    }

    if (@($values.left).Count -ne 1 -or [string]::IsNullOrWhiteSpace([string]$values.left[0])) {
        throw 'BF-939 BLOCKED: Waiver Candidate Compare requires exactly one left candidate id.'
    }
    if (@($values.right).Count -gt 1) {
        throw 'BF-939 BLOCKED: Waiver Candidate Compare accepts at most one right candidate id.'
    }

    $left = [string]$values.left[0]
    $right = if (@($values.right).Count -eq 1) { [string]$values.right[0] } else { '' }

    if ($left -notmatch '^[0-9]+$') {
        throw 'BF-939 BLOCKED: left waiver candidate id is malformed.'
    }
    if (-not [string]::IsNullOrWhiteSpace($right) -and $right -notmatch '^[0-9]+$') {
        throw 'BF-939 BLOCKED: right waiver candidate id is malformed.'
    }
    if (-not [string]::IsNullOrWhiteSpace($right) -and $left -ceq $right) {
        throw 'BF-939 BLOCKED: Waiver Candidate Compare requires two different exact candidate ids.'
    }

    return [pscustomobject]@{
        LeftId = $left
        RightId = $right
    }
}

function ConvertTo-WaiverCompareCandidateHtml {
    param(
        [Parameter(Mandatory = $true)]$Candidate,
        [Parameter(Mandatory = $true)][string]$SideLabel
    )

    $lane = Get-WaiverLanePresentation -Lane $Candidate.Lane
    $injuryText = if ($Candidate.Injury -eq 'none') { 'None reported' } else { $Candidate.Injury }
    $depthText = if ($Candidate.Depth -eq 'none/none') { 'Not available' } else { $Candidate.Depth }
    $hrefId = [System.Uri]::EscapeDataString([string]$Candidate.SleeperId)

    return @"
<article class="waiver-compare-card">
  <div class="eyebrow">$(ConvertTo-HtmlText $SideLabel)</div>
  <div class="candidate-top">
    <div><h2>$(ConvertTo-HtmlText $Candidate.Name)</h2><div class="meta">$(ConvertTo-HtmlText $Candidate.Position) &middot; NFL $(ConvertTo-HtmlText $Candidate.Team)</div></div>
    <span class="lane $($lane.Class)">$(ConvertTo-HtmlText $lane.Label)</span>
  </div>
  <div class="candidate-facts">
    <div><strong>Status</strong>$(ConvertTo-HtmlText $Candidate.Status)</div>
    <div><strong>Injury</strong>$(ConvertTo-HtmlText $injuryText)</div>
    <div><strong>Depth</strong>$(ConvertTo-HtmlText $depthText)</div>
    <div><strong>BF-616 lane</strong>$(ConvertTo-HtmlText $Candidate.Lane)</div>
  </div>
  <div class="market"><strong>Market attention:</strong> add $(ConvertTo-HtmlText $Candidate.MarketAdd) / drop $(ConvertTo-HtmlText $Candidate.MarketDrop) / net $(ConvertTo-HtmlText $Candidate.MarketNet)</div>
  <div class="actions" style="margin-top:12px"><a class="button" href="/waivers/candidate/$hrefId">View governed details</a></div>
  <details><summary>Comparator traceability</summary><div class="tech"><div>Candidate-supported comparators: $(ConvertTo-HtmlText $Candidate.SupportedComparators)</div><div>Eligible comparators: $(ConvertTo-HtmlText $Candidate.EligibleComparators)</div></div></details>
</article>
"@
}

function ConvertTo-WaiverCompareHtml {
    param(
        [Parameter(Mandatory = $true)][string]$Bundle,
        [Parameter(Mandatory = $true)]$Request
    )

    $candidates = @(Get-WaiverCandidates -Bundle $Bundle)
    $counts = Get-WaiverAuthorizedCounts -Bundle $Bundle
    if ($candidates.Count -ne $counts.Total) {
        throw "BF-939 BLOCKED: BF-616 compare pool count $($candidates.Count) does not match BF-617 authorized total $($counts.Total)."
    }

    $left = Resolve-WaiverCandidateById -Bundle $Bundle -SleeperId $Request.LeftId
    if ($null -eq $left) {
        throw "BF-939 BLOCKED: left candidate id $($Request.LeftId) is not in the current BF-616 authorized shortlist."
    }

    $target = Get-WaiverTargetView -Bundle $Bundle
    $css = Get-SharedCss
    $header = Get-HeaderHtml -Target $target.Human -Active 'waivers'
    $compareCss = @"
.waiver-compare-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;margin-top:16px}.waiver-compare-card{padding:18px;border:1px solid #2b3962;border-radius:15px;background:#0d1630}.waiver-compare-card h2{margin:5px 0 4px}.waiver-compare-choice-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:16px}.waiver-compare-choice{padding:15px;border:1px solid #2b3962;border-radius:13px;background:#0d1630}.waiver-compare-choice .name{font-size:18px;font-weight:800}.waiver-compare-choice .meta{margin-top:4px;color:#aebada;font-size:12px}@media(max-width:760px){.waiver-compare-grid,.waiver-compare-choice-grid{grid-template-columns:1fr}}
"@

    $leftHtml = ConvertTo-WaiverCompareCandidateHtml -Candidate $left -SideLabel 'Left candidate'

    if ([string]::IsNullOrWhiteSpace([string]$Request.RightId)) {
        $choices = ''
        foreach ($candidate in @($candidates)) {
            if ([string]$candidate.SleeperId -ceq [string]$left.SleeperId) { continue }
            $rightHref = [System.Uri]::EscapeDataString([string]$candidate.SleeperId)
            $choices += @"
<article class="waiver-compare-choice"><div class="name">$(ConvertTo-HtmlText $candidate.Name)</div><div class="meta">$(ConvertTo-HtmlText $candidate.Position) &middot; NFL $(ConvertTo-HtmlText $candidate.Team) &middot; $(ConvertTo-HtmlText $candidate.Lane)</div><div class="actions" style="margin-top:10px"><a class="button" href="/waivers/compare?left=$([System.Uri]::EscapeDataString([string]$left.SleeperId))&right=$rightHref">Compare with this candidate</a></div></article>
"@
        }
        if ([string]::IsNullOrWhiteSpace($choices)) {
            $choices = '<div class="subtle">No second authorized waiver candidate is available in the current BF-616 review pool.</div>'
        }

        return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Waiver Candidate Compare</title><style>$css$compareCss</style></head><body><main class="shell">
$header
<section class="panel"><div class="eyebrow">Waiver Candidate Compare</div><div class="statusrow"><div><h1 class="headline">First candidate selected</h1><p class="lede">Choose a second exact player from Butler's current BF-616 authorized review pool. This comparison is neutral context only.</p></div><span class="status done">NOT A RANKING</span></div><div class="waiver-compare-grid">$leftHtml</div><div class="actions" style="margin-top:14px"><a class="button" href="/waivers">Back to Waiver Board</a></div></section>
<section class="panel"><div class="eyebrow">Second candidate</div><h2 class="headline">Choose second candidate</h2><p class="lede">Only currently authorized BF-616 candidates are available here.</p><div class="waiver-compare-choice-grid">$choices</div></section>
<section class="panel boundary"><span class="lock">READ ONLY &middot; NOT A RANKING.</span> Waiver Candidate Compare reuses the current BF-616 authorized shortlist only. It does not score, rank, choose a winner, recommend a new ADD or DROP, set FAAB, refresh evidence, or submit a Sleeper transaction.</section>
</main></body></html>
"@
    }

    $right = Resolve-WaiverCandidateById -Bundle $Bundle -SleeperId $Request.RightId
    if ($null -eq $right) {
        throw "BF-939 BLOCKED: right candidate id $($Request.RightId) is not in the current BF-616 authorized shortlist."
    }
    if ([string]$left.SleeperId -ceq [string]$right.SleeperId) {
        throw 'BF-939 BLOCKED: Waiver Candidate Compare requires two different authorized candidates.'
    }

    $rightHtml = ConvertTo-WaiverCompareCandidateHtml -Candidate $right -SideLabel 'Right candidate'
    $leftHref = [System.Uri]::EscapeDataString([string]$left.SleeperId)
    $rightHref = [System.Uri]::EscapeDataString([string]$right.SleeperId)
    $swapHref = "/waivers/compare?left=$rightHref&right=$leftHref"

    return @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Butler - Waiver Candidate Compare</title><style>$css$compareCss</style></head><body><main class="shell">
$header
<section class="panel"><div class="eyebrow">Waiver Candidate Compare</div><div class="statusrow"><div><h1 class="headline">Side-by-side neutral waiver evidence</h1><p class="lede">Two exact players from the current BF-616 authorized review pool. Butler shows the existing evidence without selecting a better candidate.</p></div><span class="status done">NOT A RANKING</span></div><div class="actions" style="margin-top:14px"><a class="button" href="$swapHref">Swap sides</a><a class="button" href="/waivers">Compare different candidates</a></div></section>
<section class="panel"><div class="waiver-compare-grid">$leftHtml$rightHtml</div></section>
<section class="panel boundary"><span class="lock">READ ONLY &middot; NOT A RANKING.</span> Waiver Candidate Compare does not create a score, rank, winner, preference, recommendation, FAAB amount, new ADD/DROP pair, provider refresh, Butler write, or Sleeper transaction.</section>
</main></body></html>
"@
}

'@

$text = $text.Insert($helperIndex, $compareFunctions)

# Teach the read-only dashboard router about the compare surface.
$knownOld = '$knownStaticPath = $path -eq "/" -or $path -eq "/index.html" -or $path -eq "/team" -or $path -eq "/waivers"'
$knownNew = '$knownStaticPath = $path -eq "/" -or $path -eq "/index.html" -or $path -eq "/team" -or $path -eq "/waivers" -or $path -eq "/waivers/compare"'
$text = Replace-ExactlyOnce -Text $text -Old $knownOld -New $knownNew -Contract 'Waiver Candidate Compare static route allowlist'

$routeOld = @'
                elseif ($candidateMatch.Success) {
                    $candidateId = $candidateMatch.Groups['id'].Value
'@
$routeNew = @'
                elseif ($path -eq "/waivers/compare") {
                    $waiverBundle = Invoke-ButlerReadOnlyWaiverBoard
                    $compareRequest = Get-WaiverCompareRequest -RequestTarget $parts[1]
                    $html = ConvertTo-WaiverCompareHtml -Bundle $waiverBundle -Request $compareRequest
                }
                elseif ($candidateMatch.Success) {
                    $candidateId = $candidateMatch.Groups['id'].Value
'@
$text = Replace-ExactlyOnce -Text $text -Old $routeOld.TrimEnd() -New $routeNew.TrimEnd() -Contract 'Waiver Candidate Compare route'

foreach ($required in @(
    'Compare candidate',
    'function Get-WaiverCompareRequest',
    'function ConvertTo-WaiverCompareHtml',
    'Waiver Candidate Compare',
    'First candidate selected',
    'Choose second candidate',
    'Compare with this candidate',
    'Side-by-side neutral waiver evidence',
    'Swap sides',
    'READ ONLY &middot; NOT A RANKING.',
    '$path -eq "/waivers/compare"',
    'Invoke-ButlerReadOnlyWaiverBoard'
)) {
    if ($text.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-939 BLOCKED: required Waiver Candidate Compare marker is missing: $required"
    }
}

$installedStart = $text.IndexOf('function Get-WaiverCompareRequest', [System.StringComparison]::Ordinal)
$installedEnd = $text.IndexOf('function Send-HttpResponse {', $installedStart, [System.StringComparison]::Ordinal)
if ($installedStart -lt 0 -or $installedEnd -le $installedStart) {
    throw 'BF-939 BLOCKED: installed compare function boundary is missing.'
}
$installed = $text.Substring($installedStart, $installedEnd - $installedStart)
if ($installed -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|BF-641') {
    throw 'BF-939 BLOCKED: Waiver Candidate Compare introduced provider, write, FAAB, or recommendation behavior.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))

$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($DashboardPath, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $parseSummary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-939 BLOCKED: generated staged Dashboard failed PowerShell parse: $parseSummary"
}

Write-Host 'BF-939 Waiver Candidate Compare applied.'
