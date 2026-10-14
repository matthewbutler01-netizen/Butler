Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf1010-lineup-count-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $corePath = Join-Path $root 'butler-app-shell-core-single.ps1'
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($corePath, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -ne 0) { throw 'BF-1010 staged core has parse errors.' }

    foreach ($name in @('ConvertTo-HtmlText', 'ConvertTo-AutoFillView', 'Get-LineupSwapCompareHref', 'Get-MatchupLineupDecisionView', 'ConvertTo-AutoFillHtml')) {
        $function = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }.GetNewClosure(), $true)
        if ($null -eq $function) { throw "BF-1010 missing staged function: $name" }
        . ([scriptblock]::Create($function.Extent.Text))
    }

    $drakeReview = [pscustomobject]@{
        ordinal = 2; slot = 'QB'; current = 'Drake Maye'; proposed = 'Geno Smith'
        projectedGain = 'Unavailable'; status = 'MANUAL_REVIEW_REPLACEMENT'; reason = 'Attributed SIT evidence requires manager review.'
        currentUsage = 'Passing workload available'; proposedUsage = 'Passing workload available'
        currentMatchup = 'Current matchup'; proposedMatchup = 'Candidate matchup'
        commentary = 'Single attributed opinion; no consensus.'
        sources = @('https://github.com/nflverse/nflverse-data')
        currentExpert = 'SIT by Matt Okada'; proposedExpert = 'unverified'
    }
    $wrReview = [pscustomobject]@{
        ordinal = 0; slot = 'WR'; current = 'Emeka Egbuka'; proposed = 'Jauan Jennings'
        projectedGain = '0'; status = 'MANUAL_REVIEW_REPLACEMENT'; reason = 'Internal legal-slot comparison only.'
        currentUsage = 'Usage available'; proposedUsage = 'Usage available'
        currentMatchup = 'Current matchup'; proposedMatchup = 'Candidate matchup'
        commentary = 'Internal slot placement evidence.'
        sources = @('https://github.com/nflverse/nflverse-data')
        currentExpert = 'unverified'; proposedExpert = 'unverified'
    }
    $flexReview = [pscustomobject]@{
        ordinal = 1; slot = 'FLEX'; current = 'Jauan Jennings'; proposed = 'Emeka Egbuka'
        projectedGain = '0'; status = 'MANUAL_REVIEW_REPLACEMENT'; reason = 'Internal legal-slot comparison only.'
        currentUsage = 'Usage available'; proposedUsage = 'Usage available'
        currentMatchup = 'Current matchup'; proposedMatchup = 'Candidate matchup'
        commentary = 'Internal slot placement evidence.'
        sources = @('https://github.com/nflverse/nflverse-data')
        currentExpert = 'unverified'; proposedExpert = 'unverified'
    }

    $fixture = @"
State: READY
Season/week: 2026/4
Scoring basis: PPR
Projection source: Sleeper
Projection source surface: https://api.sleeper.app
Projection coverage: FULL
Current projected starter total: 89.8
Recommended projected starter total: 89.8
Projected gain: 0
Expert pick: {"playerId":"1","player":"Drake Maye","position":"QB","selection":"SIT","author":"Matt Okada","publishedAt":"2026-10-01T17:00:00Z","modifiedAt":"2026-10-01T18:00:00Z","checkedAt":"2026-10-03T22:00:00Z","source":"https://www.nfl.com/news/test-column","coverage":"Single attributed SIT selection"}
Decision review: $($wrReview | ConvertTo-Json -Compress -Depth 5)
Decision review: $($flexReview | ConvertTo-Json -Compress -Depth 5)
Decision review: $($drakeReview | ConvertTo-Json -Compress -Depth 5)
Recommended lineup:
  #0 WR | current=Emeka Egbuka [20] | recommended=Jauan Jennings [21] | projected=12 | action=CHANGE
  #0 projection_delta | current=12 | recommended=12 | gain=0
  #1 FLEX | current=Jauan Jennings [21] | recommended=Emeka Egbuka [20] | projected=12 | action=CHANGE
  #1 projection_delta | current=12 | recommended=12 | gain=0
  #2 QB | current=Drake Maye [1] | recommended=Drake Maye [1] | projected=20 | action=KEEP
  #2 projection_delta | current=20 | recommended=20 | gain=0
Moves to bench:
  none
Promotions to starting lineup:
  none
Availability exclusions:
  none
Projection holds:
  none
"@

    $parsed = ConvertTo-AutoFillView -Text $fixture
    $parsed.ProjectionHolds = @(
        [pscustomobject]@{ Name = 'KC Concepcion'; Id = '10'; Reason = 'Availability hold: Questionable' },
        [pscustomobject]@{ Name = 'Josh Jacobs'; Id = '11'; Reason = 'Projection evidence incomplete.' },
        [pscustomobject]@{ Name = 'Hunter Henry'; Id = '12'; Reason = 'Projection evidence incomplete.' }
    )

    $slotOnly = ConvertTo-AutoFillHtml -AutoFill $parsed
    if ($slotOnly -notmatch '<strong>Player holds:</strong>' -or
        $slotOnly -match '<strong>Projection hold:</strong>' -or
        $slotOnly -notmatch '<strong>KC Concepcion</strong>: Availability review\.' -or
        $slotOnly -notmatch '<strong>Josh Jacobs</strong>: Projection evidence incomplete\.') {
        throw 'Player holds must distinguish availability restrictions from missing projection evidence.'
    }

    if ($slotOnly -notmatch '1 start/sit signal needs review' -or $slotOnly -notmatch '>4 ITEMS</span>') {
        throw 'BF-1010 must count three holds plus one Drake Maye expert decision, not two internal slot placements.'
    }
    if ($slotOnly -notmatch 'Optimizer slot placement evidence \(2 placements\)' -or
        $slotOnly -notmatch 'WR.*optimizer slot placement Emeka Egbuka &rarr; Jauan Jennings' -or
        $slotOnly -notmatch 'FLEX.*optimizer slot placement Jauan Jennings &rarr; Emeka Egbuka') {
        throw 'BF-1010 must preserve both internal slot placements as supporting evidence.'
    }
    if ($slotOnly -match '<strong>Manager move 1</strong>') {
        throw 'BF-1010 must not invent a manager move when promotion/bench summaries are empty.'
    }
    if ($slotOnly -notmatch '<details id="lineup-comparison-0"[^>]*class="swap-review-card slot-placement-review">.*Slot placement evidence: WR - Emeka Egbuka &rarr; Jauan Jennings' -or
        $slotOnly -notmatch '<details id="lineup-comparison-1"[^>]*class="swap-review-card slot-placement-review">.*Slot placement evidence: FLEX - Jauan Jennings &rarr; Emeka Egbuka') {
        throw 'BF-1012 must collapse both internal WR/FLEX comparison cards behind slot-placement evidence summaries.'
    }
    if ($slotOnly -match '<section id="lineup-comparison-(0|1)"') {
        throw 'BF-1012 internal slot-placement comparisons must not remain expanded standalone sections.'
    }

    if ($slotOnly -notmatch '<h3>What should I change\?</h3><p>Review Drake Maye vs. Geno Smith\. No lineup change recommended yet\.</p>') {
        throw 'Start/Sit summary must name the existing comparison without recommending a swap.'
    }
    $parsed.SwapReviews = @($wrReview, $flexReview)
    $noCandidate = ConvertTo-AutoFillHtml -AutoFill $parsed
    if ($noCandidate -notmatch "Review Drake Maye&#39;s SIT evidence\. No lineup change recommended yet\.") {
        throw 'Start/Sit summary must fall back to source review when no candidate exists.'
    }
    $parsed.SwapReviews = @($wrReview, $flexReview, $drakeReview)

    $parsed.Promotions = @([pscustomobject]@{ Name = 'Jauan Jennings' })
    $parsed.BenchMoves = @([pscustomobject]@{ Name = 'Emeka Egbuka' })
    $oneMove = ConvertTo-AutoFillHtml -AutoFill $parsed
    if ($oneMove -notmatch '1 start/sit signal needs review' -or $oneMove -notmatch '>5 ITEMS</span>') {
        throw 'BF-1010 must collapse two internal slot placements into one actual manager move.'
    }
    if ($oneMove -notmatch '<strong>Manager move 1</strong>: review Emeka Egbuka &rarr; Jauan Jennings' -or
        $oneMove -notmatch 'Optimizer slot placement evidence \(2 placements\)') {
        throw 'BF-1010 manager move summary or supporting slot-placement evidence is missing.'
    }

    if ($oneMove -match '<h3>What should I change\?</h3><p>[^<]*No lineup change recommended yet') {
        throw 'Start/Sit summary must preserve actual manager moves.'
    }
    Write-Host 'BF-1010 LINEUP SLOT-PLACEMENT DECISION COUNT AND START/SIT SUMMARY: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}
