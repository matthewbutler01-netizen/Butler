Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf943-render-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }
    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $root 'butler-app-shell-core-single.ps1'), [ref]$tokens, [ref]$errors)
    if (@($errors).Count -ne 0) { throw 'Staged core has parse errors.' }
    foreach ($name in @('ConvertTo-HtmlText', 'ConvertTo-AutoFillView', 'Get-LineupSwapCompareHref', 'Get-MatchupLineupDecisionView', 'ConvertTo-AutoFillHtml')) {
        $function = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }.GetNewClosure(), $true)
        if ($null -eq $function) { throw "Missing staged function: $name" }
        . ([scriptblock]::Create($function.Extent.Text))
    }
    $notRequested = ConvertTo-AutoFillHtml -AutoFill ([pscustomobject]@{ Requested = $false })
    if ($notRequested -notmatch 'NOT REVIEWED') { throw 'Unrequested state lost.' }
    # Deliberately omit Assignments: incomplete evidence must return before reading counts.
    $gap = ConvertTo-AutoFillHtml -AutoFill ([pscustomobject]@{
        Requested = $true; Ready = $false; Week = 4; Scoring = 'PPR'; Reason = 'Weekly projection evidence unavailable'
    })
    if ($gap -notmatch 'PROJECTIONS NEEDED' -or $gap -match 'CHANGES FIRST') { throw 'Projection-gap state lost.' }
    $review = ConvertTo-AutoFillHtml -AutoFill ([pscustomobject]@{
        Requested = $true; Ready = $true; Week = 4; Scoring = 'PPR'; Reason = ''
        Assignments = @(); BenchMoves = @(); Promotions = @(); AvailabilityExclusions = @(); ProjectionCoverage = 'PARTIAL'
        ProjectionHolds = @([pscustomobject]@{ Name = 'Example Player'; Reason = 'Questionable; checked=2026-10-01; practice=Limited' })
        DecisionEvidence = @('Example production: receptions=3; expert advice not verified <unsafe>')
        CurrentTotal = '10'; RecommendedTotal = '10'; Gain = '0'; Source = 'Sleeper'
    })
    if ($review -notmatch 'receptions=3' -or $review -notmatch 'practice=Limited' -or
        $review -notmatch '&lt;unsafe&gt;' -or $review -match '<unsafe>') {
        throw 'Decision or injury evidence is missing or not HTML escaped.'
    }
    if ($review -notmatch 'NO PROPOSALS' -or $review -match 'ALL KEEP' -or
        $review -notmatch 'Review holds and evidence gaps' -or $review -notmatch 'expert selections') {
        throw 'Partial review must retain evidence gaps instead of implying a complete all-keep decision.'
    }
    $structured = [pscustomobject]@{
        ordinal = 0; slot = 'WR'; current = 'Current Player'; proposed = 'Candidate <unsafe>'
        projectedGain = '0.25'; status = 'WITHHELD_USAGE_CONFLICT'; reason = 'Conflicting workload needs review'
        currentUsage = 'Week 2: targets 6, passing attempts 32; Week 3: targets 9, passing attempts 41'; proposedUsage = 'Week 2: targets 7, passing attempts unavailable; Week 3: targets 2, passing attempts 0'
        currentMatchup = 'Saved team KC; week 4 vs LV'; proposedMatchup = 'Missing <coverage>'
        commentary = 'Expert picks unverified'; sources = @('https://github.com/nflverse/nflverse-data', 'javascript:alert(1)')
    }
    $fixture = @"
State: READY
Season/week: 2026/4
Scoring basis: PPR
Projection source: Sleeper
Projection source surface: https://api.sleeper.app
Projection coverage: FULL
Current projected starter total: 10
Recommended projected starter total: 10.25
Projected gain: +0.25
Expert pick: {"playerId":"2","player":"Gap <unsafe>","position":"WR","selection":"UNVERIFIED","author":"","publishedAt":"","modifiedAt":"","checkedAt":"2026-10-01T07:00:00Z","source":"javascript:alert(1)","coverage":"Missing <data>"}
Expert pick: {"playerId":"1","player":"Current <unsafe>","position":"WR","selection":"SIT","author":"Test Author","publishedAt":"2026-09-30T17:00:00Z","modifiedAt":"2026-09-30T18:00:00Z","checkedAt":"2026-10-01T07:00:00Z","source":"https://www.nfl.com/news/test-column","coverage":"Single author; review required"}
Decision review: $($structured | ConvertTo-Json -Compress -Depth 5)
Recommended lineup:
  #0 WR | current=Current Player [1] | recommended=Candidate [2] | projected=10.25 | action=CHANGE
  #0 projection_delta | current=10 | recommended=10.25 | gain=+0.25
Moves to bench:
  Current Player [1]
Promotions to starting lineup:
  Candidate [2]
Availability exclusions:
  none
Projection holds:
  none
"@
    $parsed = ConvertTo-AutoFillView -Text $fixture
    if (@($parsed.ExpertPicks).Count -ne 2) { throw 'Expert pick parser lost attribution.' }
    if (@($parsed.SwapReviews).Count -ne 1) { throw 'Structured swap review parser lost the exact review.' }
    $rendered = ConvertTo-AutoFillHtml -AutoFill $parsed
    if ($rendered -notmatch 'Attributed expert selections and coverage' -or $rendered -notmatch 'Test Author' -or $rendered -notmatch 'Current &lt;unsafe&gt;: SIT' -or $rendered -notmatch 'NFL.com weekly column') { throw 'Expert selections, escaping or attribution missing.' }
    if ($rendered -notmatch 'View 1 player with unverified coverage' -or
        $rendered -notmatch 'Source and dates' -or $rendered -notmatch 'Missing &lt;data&gt;' -or
        $rendered.IndexOf('Current &lt;unsafe&gt;: SIT') -gt $rendered.IndexOf('Gap &lt;unsafe&gt;: UNVERIFIED')) {
        throw 'Explicit expert selections must precede collapsed, escaped coverage gaps.'
    }
    if ($rendered -notmatch 'Review queue' -or
        $rendered -notmatch 'review the projection proposal Current Player &rarr; Candidate.*Expert signal: attributed SIT selection from Test Author.*href="#lineup-expert-1">Review expert source</a>') {
        throw 'Review queue must join expert selections to changed starters by exact ID, even when names differ.'
    }
    $savedChanged = $parsed.Assignments[0].Changed
    $parsed.Assignments[0].Changed = $false
    $noProposal = ConvertTo-AutoFillHtml -AutoFill $parsed
    if ($noProposal -notmatch 'current starter with an attributed SIT selection' -or $noProposal -match 'review the projection proposal') { throw 'Starter expert conflict must remain visible without a projection proposal.' }
    $parsed.Assignments[0].Changed = $savedChanged
    $originalPicks = $parsed.ExpertPicks
    $parsed.ExpertPicks = @($originalPicks) + @($originalPicks | Where-Object { $_.playerId -ceq '1' })
    $ambiguous = ConvertTo-AutoFillHtml -AutoFill $parsed
    if ($ambiguous -match 'current starter with an attributed SIT selection' -or
        $ambiguous -match 'Expert signal: attributed SIT selection') {
        throw 'Duplicate expert IDs must not establish a starter conflict.'
    }
    $parsed.ExpertPicks = $originalPicks
    $savedReviewStatus = $parsed.SwapReviews[0].status
    $parsed.SwapReviews[0].status = 'MANUAL_REVIEW_REPLACEMENT'
    $parsed.SwapReviews[0] | Add-Member -NotePropertyName currentExpert -NotePropertyValue 'SIT by Author <unsafe>' -Force
    $parsed.SwapReviews[0] | Add-Member -NotePropertyName proposedExpert -NotePropertyValue 'unverified' -Force
    $replacementHtml = ConvertTo-AutoFillHtml -AutoFill $parsed
    if ($replacementHtml -notmatch '<summary>Bench comparison:' -or $replacementHtml -notmatch 'BENCH ALTERNATIVE: REVIEW ONLY') { throw 'Replacement comparisons must be collapsed and labeled review-only.' }
    if ($replacementHtml -notmatch 'Comparison limits and evidence gaps' -or $replacementHtml -notmatch 'SIT by Author &lt;unsafe&gt;' -or $replacementHtml -notmatch 'passing attempts are shown separately when available') { throw 'Compact comparison must preserve escaped expert signals and explain usage limits.' }
    if ($replacementHtml -notmatch 'passing attempts 41' -or $replacementHtml -notmatch 'passing attempts unavailable' -or $replacementHtml -notmatch 'passing attempts 0') { throw 'Passing workload must preserve observed values, explicit zero and missing coverage.' }
    $parsed.SwapReviews[0].status = $savedReviewStatus
    $savedPicks = $parsed.ExpertPicks
    $parsed.ExpertPicks = @($savedPicks | Where-Object { $_.selection -ceq 'UNVERIFIED' })
    $gapsOnly = ConvertTo-AutoFillHtml -AutoFill $parsed
    if ($gapsOnly -notmatch 'No verified expert selections available.' -or $gapsOnly -match 'class="expert-selection"') {
        throw 'Missing expert coverage must not appear as a verified selection.'
    }
    $parsed.ExpertPicks = $savedPicks
    if ($rendered -notmatch 'NFL opponent and observed defense' -or $rendered -notmatch 'Missing &lt;coverage&gt;') { throw 'NFL matchup evidence missing or unescaped.' }
    if ($rendered -notmatch 'WITHHELD: USAGE CONFLICT' -or $rendered -notmatch '<table' -or
        $rendered -notmatch 'targets 9' -or $rendered -notmatch 'Sources and commentary' -or
        $rendered -notmatch '&lt;unsafe&gt;' -or $rendered -match '<unsafe>|javascript:|Make 1 lineup|<h3>Start</h3>|<h3>Sit</h3>') {
        throw 'Structured comparison, manual-review wording, escaping, or source-link safety failed.'
    }
    $matchup = Get-MatchupLineupDecisionView -AutoFill $parsed
    if ($matchup.Status -cne 'MANUAL REVIEW' -or $matchup.Title -match '^Make ' -or
        $matchup.ActionHref -cne '/team/autofill') { throw 'Matchup decision qualification or review navigation failed.' }
    $emptyFixture = $fixture.Replace('current=Current Player [1]', 'current=Empty slot [0]').Replace('current=10 | recommended=10.25 | gain=+0.25', 'current=UNAVAILABLE | recommended=10.25 | gain=UNAVAILABLE')
    $emptyParsed = ConvertTo-AutoFillView -Text $emptyFixture
    $emptyHtml = ConvertTo-AutoFillHtml -AutoFill $emptyParsed
    if ($emptyParsed.Assignments[0].CurrentId -cne '0' -or $emptyParsed.Assignments[0].CurrentPoints -cne 'UNAVAILABLE' -or $emptyHtml -notmatch 'Empty slot') { throw 'Explicit empty slot must render without inventing current points.' }
    foreach ($html in @($review, $rendered, $replacementHtml, $ambiguous, $emptyHtml)) {
        $ids = @([regex]::Matches($html, 'id="(lineup-(?:hold|expert|comparison)-[0-9]+)"') | ForEach-Object { $_.Groups[1].Value })
        if (@($ids | Select-Object -Unique).Count -ne $ids.Count) { throw 'Review evidence anchors must be unique, including duplicate expert objects.' }
        foreach ($link in [regex]::Matches($html, 'href="#(lineup-(?:hold|expert|comparison)-[0-9]+)"')) {
            if ($link.Groups[1].Value -cnotin $ids) { throw 'Review queue link has no evidence target.' }
        }
    }
    if ($review -notmatch 'href="#lineup-hold-0"' -or $replacementHtml -notmatch 'href="#lineup-comparison-0"' -or
        $replacementHtml -notmatch 'href="#lineup-expert-1"') { throw 'Review queue must link hold, comparison and exact-player expert evidence.' }
    if ($replacementHtml -notmatch 'href="#lineup-review-queue"' -or $replacementHtml -notmatch 'id="lineup-review-queue"') {
        throw 'Comparison evidence must provide a return to the review queue.'
    }
    $savedGain = $parsed.SwapReviews[0].projectedGain
    $parsed.SwapReviews[0].projectedGain = 'Unavailable'
    $unavailableDelta = ConvertTo-AutoFillHtml -AutoFill $parsed
    if ($unavailableDelta -match 'Unavailable points' -or $unavailableDelta -notmatch 'Projected slot change unavailable; comparable player projections are incomplete.') {
        throw 'Missing comparison delta must explain the evidence gap without presenting a point value.'
    }
    $parsed.SwapReviews[0].projectedGain = $savedGain
    foreach ($compactHtml in @($rendered, $replacementHtml)) {
        if ($compactHtml -notmatch '<details class="lineup-evidence-section"><summary>Recent observed usage</summary>' -or
            $compactHtml -notmatch '<table class="swap-usage-table"><caption>Recent observed usage</caption>') {
            throw 'BF-947 compact usage evidence disclosure is missing.'
        }
        if ($compactHtml.IndexOf('Projected slot change', [System.StringComparison]::Ordinal) -gt
            $compactHtml.IndexOf('<summary>Recent observed usage</summary>', [System.StringComparison]::Ordinal)) {
            throw 'BF-947 must keep the decision summary ahead of dense usage evidence.'
        }
    }
    if ($replacementHtml -notmatch 'lineup-evidence-section' -or $replacementHtml -notmatch 'Back to review queue') {
        throw 'BF-947 must preserve compact evidence and review-queue return navigation together.'
    }
    if ($review -notmatch 'Review 1 unresolved item' -or $review -notmatch '>1 ITEM</span>') {
        throw 'BF-948 single unresolved hold must drive the first-scan decision title and queue badge.'
    }
    if ($rendered -notmatch 'Review 1 unresolved item' -or $rendered -notmatch '>1 ITEM</span>') {
        throw 'BF-950 must count the changed-starter expert signal as evidence for the proposal, not a second decision.'
    }
    if ($rendered -match 'Review WR comparisons:' -or
        $rendered -match 'current starter with an attributed SIT selection' -or
        $rendered -notmatch 'review the projection proposal Current Player &rarr; Candidate.*Expert signal: attributed SIT selection from Test Author.*href="#lineup-expert-1">Review expert source</a>.*Comparison evidence:.*href="#lineup-comparison-0"') {
        throw 'BF-950 must keep expert and comparison evidence inside the single projection-proposal queue item.'
    }
    $parsed.Assignments[0].Changed = $false
    $expertAndComparison = ConvertTo-AutoFillHtml -AutoFill $parsed
    if ($expertAndComparison -notmatch 'Review 1 unresolved item' -or
        $expertAndComparison -notmatch 'current starter with an attributed SIT selection' -or
        $expertAndComparison -notmatch 'Comparison evidence:.*href="#lineup-comparison-0"' -or
        $expertAndComparison -match 'review comparison evidence') {
        throw 'BF-951 must merge unchanged-starter expert and comparison evidence into one review task.'
    }
    $savedReviews = $parsed.SwapReviews
    $parsed.SwapReviews = @()
    $expertWithoutComparison = ConvertTo-AutoFillHtml -AutoFill $parsed
    if ($expertWithoutComparison -notmatch 'Review 1 unresolved item' -or
        $expertWithoutComparison -notmatch 'current starter with an attributed SIT selection' -or
        $expertWithoutComparison -match 'Comparison evidence:') {
        throw 'BF-951 must preserve the standalone expert review task when no comparison evidence exists.'
    }
    $savedDecisionEvidence = $parsed.DecisionEvidence
    $parsed.DecisionEvidence = @('Replacement review for Current Player: no eligible <bench> alternative remains after review holds.')
    $replacementContext = ConvertTo-AutoFillHtml -AutoFill $parsed
    if ($replacementContext -notmatch 'Review 1 unresolved item' -or
        $replacementContext -notmatch '<summary>Replacement search context</summary>' -or
        $replacementContext -notmatch 'no eligible &lt;bench&gt; alternative remains' -or
        $replacementContext -match '>2 ITEMS</span>') {
        throw 'BF-952 replacement-search context must remain visible without inflating the unresolved decision count.'
    }
    $parsed.DecisionEvidence = $savedDecisionEvidence
    $savedHolds = $parsed.ProjectionHolds
    $parsed.DecisionEvidence = @()
    $parsed.SwapReviews = @()
    $parsed.Assignments[0].Changed = $false
    $parsed.ProjectionHolds = @([pscustomobject]@{
        Name = 'Current Player'
        Id = '1'
        Reason = 'Questionable; checked=2026-10-01; practice=Limited'
    })
    $holdExpert = ConvertTo-AutoFillHtml -AutoFill $parsed
    if ($holdExpert -notmatch 'Review 1 unresolved item' -or
        $holdExpert -notmatch 'review hold' -or
        $holdExpert -notmatch 'Expert signal: attributed SIT selection from Test Author' -or
        $holdExpert -notmatch 'href="#lineup-expert-1">Review expert source</a>' -or
        $holdExpert -match 'current starter with an attributed SIT selection') {
        throw 'BF-953 must merge an exact-ID starter expert signal into the existing hold task.'
    }
    $parsed.ProjectionHolds = @([pscustomobject]@{
        Name = 'Other Held Player'
        Id = '999'
        Reason = 'Questionable; checked=2026-10-01; practice=Limited'
    })
    $differentHold = ConvertTo-AutoFillHtml -AutoFill $parsed
    if ($differentHold -notmatch 'Review 2 unresolved items' -or
        $differentHold -notmatch 'current starter with an attributed SIT selection') {
        throw 'BF-953 must not merge expert evidence into a hold with a different player ID.'
    }
    $parsed.ProjectionHolds = $savedHolds
    $parsed.DecisionEvidence = $savedDecisionEvidence
    $parsed.SwapReviews = $savedReviews
    $parsed.Assignments[0].Changed = $savedChanged
    Write-Host 'BF-943 LINEUP RENDER ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}
