Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-dashboard-bf962-waiver-comparison-mode-bridge-transform.ps1'
$transformTokens = $null
$transformErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$transformTokens, [ref]$transformErrors)
if (@($transformErrors).Count -ne 0) {
    $summary = (@($transformErrors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-962 BLOCKED: transform script failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf962-waiver-mode-bridge-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    $dashboardPath = Join-Path $root 'butler-dashboard.ps1'
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($dashboardPath, [ref]$tokens, [ref]$errors)
    if (@($errors).Count -ne 0) {
        throw 'BF-962 BLOCKED: staged Dashboard has parse errors.'
    }

    $compareFunctions = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-WaiverCompareHtml'
    }, $true))
    if ($compareFunctions.Count -ne 1) {
        throw "BF-962 BLOCKED: expected one Candidate Compare renderer, found $($compareFunctions.Count)."
    }
    $compare = $compareFunctions[0].Extent.Text

    foreach ($required in @(
        'href="/waivers/roster-compare?candidate=$leftHref">Compare to roster</a>',
        'href="/waivers/roster-compare?candidate=$leftHref">Left vs roster</a>',
        'href="/waivers/roster-compare?candidate=$rightHref">Right vs roster</a>',
        'href="/waivers/candidate/$leftHref">Open left candidate</a>',
        'href="/waivers/candidate/$rightHref">Open right candidate</a>'
    )) {
        if ($compare.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-962 BLOCKED: staged Candidate Compare bridge marker is missing: $required"
        }
    }

    $rosterFunctions = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-WaiverRosterCompareHtml'
    }, $true))
    if ($rosterFunctions.Count -ne 1) {
        throw "BF-962 BLOCKED: expected one Roster Compare renderer, found $($rosterFunctions.Count)."
    }
    $roster = $rosterFunctions[0].Extent.Text

    $bridgeCount = [regex]::Matches(
        $roster,
        [regex]::Escape('href="/waivers/compare?left=$candidateHref">Compare with waiver candidate</a>')
    ).Count
    if ($bridgeCount -ne 2) {
        throw "BF-962 BLOCKED: Roster Compare must expose Candidate Compare in both states; found $bridgeCount."
    }

    $returnCount = [regex]::Matches(
        $roster,
        [regex]::Escape('href="/waivers/candidate/$candidateHref">Back to candidate</a>')
    ).Count
    if ($returnCount -ne 2) {
        throw "BF-962 BLOCKED: BF-961 exact candidate return must remain in both Roster Compare states; found $returnCount."
    }

    $surface = $compare + $roster
    if ($surface -match 'Invoke-RestMethod|Invoke-WebRequest|https://api\.sleeper\.app|Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer') {
        throw 'BF-962 BLOCKED: comparison mode bridge introduced provider, optimizer, FAAB, or write behavior.'
    }

    Write-Host 'BF-962 WAIVER COMPARISON MODE BRIDGE ACCEPTANCE: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
