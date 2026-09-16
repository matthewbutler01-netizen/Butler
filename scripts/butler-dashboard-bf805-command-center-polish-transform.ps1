param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-805 BLOCKED: staged Butler dashboard not found at $DashboardPath"
}

$text = [System.IO.File]::ReadAllText($DashboardPath)

$headerStart = $text.IndexOf('function Get-HeaderHtml {', [System.StringComparison]::Ordinal)
$headerEnd = $text.IndexOf('function ConvertTo-DashboardHtml {', $headerStart, [System.StringComparison]::Ordinal)
if ($headerStart -lt 0 -or $headerEnd -le $headerStart) {
    throw 'BF-805 BLOCKED: dashboard header function boundary is missing.'
}

$headerReplacement = @'
function Get-HeaderHtml {
    param([AllowNull()][string]$Target, [Parameter(Mandatory=$true)][string]$Active)
    $dashboardClass = if ($Active -eq "dashboard") { "active" } else { "" }
    $teamClass = if ($Active -eq "team") { "active" } else { "" }
    $waiversClass = if ($Active -eq "waivers") { "active" } else { "" }
    $leagueClass = if ($Active -eq "league") { "active" } else { "" }
    $tradeClass = if ($Active -eq "trade") { "active" } else { "" }
    $historyClass = if ($Active -eq "history") { "active" } else { "" }
    return @"
<header class="top">
  <div class="brand"><h1>BUTLER</h1><p>We're here to serve you. Less Research. Better Decisions.</p></div>
  <div class="target">$(ConvertTo-HtmlText $Target)</div>
</header>
<nav class="nav" aria-label="Butler sections">
  <a class="$dashboardClass" href="/">Dashboard</a>
  <a class="$teamClass" href="/team">My Team</a>
  <a class="$waiversClass" href="/waivers">Waiver Board</a>
  <a class="$leagueClass" href="/league">League</a>
  <a class="$tradeClass" href="/trade">Trade Lab</a>
  <a class="$historyClass" href="/history">History</a>
</nav>
"@
}

'@

$text = $text.Substring(0, $headerStart) + $headerReplacement + $text.Substring($headerEnd)

$oldWhy = @'
    $whyCopy = if ($explanation.Ready) {
        $explanation.ExplanationText
    }
    elseif ($state -ceq "NO_TRANSACTION_TO_ACT_ON") {
        "Butler did not capture a separate persisted explanation for this no-transaction audit. The governed decision remains no move."
    }
    else {
        "No persisted explanation is available for this exact saved decision. Butler will not invent one from incomplete evidence."
    }
'@

$newWhy = @'
    $whyCopy = switch ($state) {
        "CURRENT_AND_ACTIONABLE" { "Butler found one add/drop move that passed the current governed checks. Review the move and supporting evidence before deciding whether to act." }
        "CURRENT_REFRESH_RECOMMENDED" { "Butler has a saved add/drop recommendation, but the supporting evidence is old enough that refreshing it first is recommended." }
        "TRANSACTION_ALREADY_COMPLETE" { "Sleeper shows the saved transaction as complete. No further action is needed for this move." }
        "TRANSACTION_PENDING_DO_NOT_DUPLICATE" { "Sleeper is already processing this transaction. Do not submit the same move again." }
        "STALE_DO_NOT_ACT" { "The saved move no longer passes Butler's current safety checks. Do not act on it until Butler produces a new governed decision." }
        "NO_TRANSACTION_TO_ACT_ON" { "Butler could not identify one clear add/drop move supported strongly enough by the current evidence, so no move is recommended." }
        "NO_AUDITED_DECISION" { "Butler does not currently have a saved governed decision to act on." }
        default { "Butler does not currently have enough governed evidence for an actionable recommendation." }
    }
'@

$whyMatches = [regex]::Matches($text, [regex]::Escape($oldWhy)).Count
if ($whyMatches -ne 1) {
    throw "BF-805 BLOCKED: expected exactly one BF-804 manager explanation contract, found $whyMatches."
}
$text = $text.Replace($oldWhy, $newWhy)

if ($text -notmatch 'href="/league">League</a>') {
    throw 'BF-805 BLOCKED: League navigation was not installed.'
}
if ($text -notmatch 'href="/trade">Trade Lab</a>') {
    throw 'BF-805 BLOCKED: Trade Lab navigation was not installed.'
}
if ($text -notmatch 'href="/history">History</a>') {
    throw 'BF-805 BLOCKED: History navigation was not installed.'
}
if ($text -match '\$whyCopy = if \(\$explanation\.Ready\)') {
    throw 'BF-805 BLOCKED: Command Center still binds normal-view copy directly to persisted implementation-heavy explanation text.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))
