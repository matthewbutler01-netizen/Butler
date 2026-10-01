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
    foreach ($name in @('ConvertTo-HtmlText', 'ConvertTo-AutoFillHtml')) {
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
        $review -notmatch 'Review holds and evidence gaps' -or $review -notmatch 'expert start/sit picks') {
        throw 'Partial review must retain evidence gaps instead of implying a complete all-keep decision.'
    }
    Write-Host 'BF-943 LINEUP RENDER ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}
