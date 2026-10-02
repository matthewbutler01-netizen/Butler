param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-962 BLOCKED: staged Butler dashboard not found at $DashboardPath"
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
        throw "BF-962 BLOCKED: $Contract expected one match, found $count."
    }
    return $Text.Replace($Old, $New)
}

function Get-ExactFunctionExtent {
    param(
        [Parameter(Mandatory = $true)]$Ast,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $matches = @($Ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq $Name
    }, $true))

    if ($matches.Count -ne 1) {
        throw "BF-962 BLOCKED: expected exactly one $Name function, found $($matches.Count)."
    }

    return $matches[0]
}

$text = [System.IO.File]::ReadAllText($DashboardPath)

$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseInput($text, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-962 BLOCKED: staged Dashboard failed pre-transform parse: $summary"
}

$compareAst = Get-ExactFunctionExtent -Ast $ast -Name 'ConvertTo-WaiverCompareHtml'
$compareStart = $compareAst.Extent.StartOffset
$compareEnd = $compareAst.Extent.EndOffset
$compare = $text.Substring($compareStart, $compareEnd - $compareStart)

$comparePickOld = '<div class="actions" style="margin-top:14px"><a class="button" href="/waivers/candidate/$leftHref">Back to candidate</a><a class="button" href="/waivers">Back to Waiver Board</a></div>'
$comparePickNew = '<div class="actions" style="margin-top:14px"><a class="button" href="/waivers/roster-compare?candidate=$leftHref">Compare to roster</a><a class="button" href="/waivers/candidate/$leftHref">Back to candidate</a><a class="button" href="/waivers">Back to Waiver Board</a></div>'
$compare = Replace-ExactlyOnce -Text $compare -Old $comparePickOld -New $comparePickNew -Contract 'candidate compare first-step mode bridge'

$compareDoneOld = '<div class="actions" style="margin-top:14px"><a class="button" href="$swapHref">Swap sides</a><a class="button" href="/waivers/candidate/$leftHref">Open left candidate</a><a class="button" href="/waivers/candidate/$rightHref">Open right candidate</a><a class="button" href="/waivers">Compare different candidates</a></div>'
$compareDoneNew = '<div class="actions" style="margin-top:14px"><a class="button" href="$swapHref">Swap sides</a><a class="button" href="/waivers/roster-compare?candidate=$leftHref">Left vs roster</a><a class="button" href="/waivers/roster-compare?candidate=$rightHref">Right vs roster</a><a class="button" href="/waivers/candidate/$leftHref">Open left candidate</a><a class="button" href="/waivers/candidate/$rightHref">Open right candidate</a><a class="button" href="/waivers">Compare different candidates</a></div>'
$compare = Replace-ExactlyOnce -Text $compare -Old $compareDoneOld -New $compareDoneNew -Contract 'candidate compare completed mode bridge'

$text = $text.Substring(0, $compareStart) + $compare + $text.Substring($compareEnd)

$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseInput($text, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-962 BLOCKED: Dashboard failed parse after Candidate Compare bridge: $summary"
}

$rosterAst = Get-ExactFunctionExtent -Ast $ast -Name 'ConvertTo-WaiverRosterCompareHtml'
$rosterStart = $rosterAst.Extent.StartOffset
$rosterEnd = $rosterAst.Extent.EndOffset
$roster = $text.Substring($rosterStart, $rosterEnd - $rosterStart)

$rosterPickOld = '<div class="actions" style="margin-top:14px"><a class="button" href="/waivers/candidate/$candidateHref">Back to candidate</a><a class="button" href="/waivers">Back to Waiver Board</a></div>'
$rosterPickNew = '<div class="actions" style="margin-top:14px"><a class="button" href="/waivers/compare?left=$candidateHref">Compare with waiver candidate</a><a class="button" href="/waivers/candidate/$candidateHref">Back to candidate</a><a class="button" href="/waivers">Back to Waiver Board</a></div>'
$roster = Replace-ExactlyOnce -Text $roster -Old $rosterPickOld -New $rosterPickNew -Contract 'roster compare first-step mode bridge'

$rosterDoneOld = '<div class="actions" style="margin-top:14px"><a class="button" href="/waivers/roster-compare?candidate=$candidateHref">Compare another roster player</a><a class="button" href="/waivers/candidate/$candidateHref">Back to candidate</a><a class="button" href="/waivers">Back to Waiver Board</a></div>'
$rosterDoneNew = '<div class="actions" style="margin-top:14px"><a class="button" href="/waivers/roster-compare?candidate=$candidateHref">Compare another roster player</a><a class="button" href="/waivers/compare?left=$candidateHref">Compare with waiver candidate</a><a class="button" href="/waivers/candidate/$candidateHref">Back to candidate</a><a class="button" href="/waivers">Back to Waiver Board</a></div>'
$roster = Replace-ExactlyOnce -Text $roster -Old $rosterDoneOld -New $rosterDoneNew -Contract 'roster compare completed mode bridge'

$text = $text.Substring(0, $rosterStart) + $roster + $text.Substring($rosterEnd)

$tokens = $null
$parseErrors = $null
$finalAst = [System.Management.Automation.Language.Parser]::ParseInput($text, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    $summary = (@($parseErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-962 BLOCKED: generated staged Dashboard failed PowerShell parse: $summary"
}

$installedCompare = (Get-ExactFunctionExtent -Ast $finalAst -Name 'ConvertTo-WaiverCompareHtml').Extent.Text
$installedRoster = (Get-ExactFunctionExtent -Ast $finalAst -Name 'ConvertTo-WaiverRosterCompareHtml').Extent.Text

foreach ($required in @(
    'href="/waivers/roster-compare?candidate=$leftHref">Compare to roster</a>',
    'href="/waivers/roster-compare?candidate=$leftHref">Left vs roster</a>',
    'href="/waivers/roster-compare?candidate=$rightHref">Right vs roster</a>'
)) {
    if ($installedCompare.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
        throw "BF-962 BLOCKED: Candidate Compare bridge marker is missing: $required"
    }
}

$rosterBridgeCount = [regex]::Matches(
    $installedRoster,
    [regex]::Escape('href="/waivers/compare?left=$candidateHref">Compare with waiver candidate</a>')
).Count
if ($rosterBridgeCount -ne 2) {
    throw "BF-962 BLOCKED: Roster Compare must expose the candidate-compare bridge in both states; found $rosterBridgeCount."
}

$bf962Surface = $comparePickNew + $compareDoneNew + $rosterPickNew + $rosterDoneNew
if ($bf962Surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
    throw 'BF-962 BLOCKED: comparison mode bridge introduced provider, optimizer, FAAB, or write behavior.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))
Write-Host 'BF-962 Waiver comparison mode bridge applied.'
