Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$transformPath = Join-Path $PSScriptRoot 'butler-app-bf993-dashboard-glance-scanability-transform.ps1'
$tokens = $null
$errors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($transformPath, [ref]$tokens, [ref]$errors)
if (@($errors).Count -ne 0) {
    $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
    throw "BF-993 BLOCKED: transform failed PowerShell parse before staging: $summary"
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf993-dashboard-glance-' + [guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    foreach ($name in @('butler-dashboard.ps1', 'butler-app-shell-core-single.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $root
    }

    & (Join-Path $PSScriptRoot 'butler-dashboard-bf715-transform.ps1') -DashboardPath (Join-Path $root 'butler-dashboard.ps1')

    function Get-OneFunction {
        param(
            [Parameter(Mandatory = $true)][string]$Path,
            [Parameter(Mandatory = $true)][string]$Name
        )

        $tokens = $null
        $errors = $null
        $ast = [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$errors)
        if (@($errors).Count -ne 0) {
            $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
            throw "BF-993 BLOCKED: staged file failed PowerShell parse: $Path :: $summary"
        }

        $matches = @($ast.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -eq $Name
        }, $true))
        if ($matches.Count -ne 1) {
            throw "BF-993 BLOCKED: expected one $Name function, found $($matches.Count)."
        }
        return $matches[0].Extent.Text
    }

    $dashboard = Get-OneFunction -Path (Join-Path $root 'butler-dashboard.ps1') -Name 'ConvertTo-DashboardHtml'
    $matchup = Get-OneFunction -Path (Join-Path $root 'butler-app-shell-core-single.ps1') -Name 'Add-DashboardMatchupSummary'

    foreach ($required in @(
        'Copy = "Opponent, week, and matchup context for the current roster."',
        'Copy = [string]$bf907LineupView[1]',
        'Copy = [string]$bf907WaiverView[1]',
        '<div class="subtle">$(ConvertTo-HtmlText $bf907Item.Copy)</div>'
    )) {
        if ($dashboard.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-993 BLOCKED: staged Dashboard glance marker is missing: $required"
        }
    }

    foreach ($required in @(
        '$copy = ''Matchup evidence is unavailable or stale; open the matchup to review the current frame.''',
        '$copy = ''Opponent and week are verified for this roster; open the matchup for full context.''',
        '<div class="subtle">'' + (ConvertTo-HtmlText $copy) + ''</div>',
        'href="/matchup">Open Weekly Matchup &rarr;</a>',
        '<strong>Opponent tools</strong>'
    )) {
        if ($matchup.IndexOf($required, [System.StringComparison]::Ordinal) -lt 0) {
            throw "BF-993 BLOCKED: staged Matchup glance marker is missing: $required"
        }
    }

    foreach ($forbidden in @(
        '$matchupPrimaryHref',
        '$matchupPrimaryLabel',
        '$lineupCardMatch',
        'href="/team/autofill">Review Lineup &rarr;</a>'
    )) {
        if ($matchup.IndexOf($forbidden, [System.StringComparison]::Ordinal) -ge 0) {
            throw "BF-993 BLOCKED: BF-992 matchup/lineup separation regressed: $forbidden"
        }
    }

    $readCount = [regex]::Matches($matchup, [regex]::Escape('Invoke-ButlerReadOnlyTask')).Count
    if ($readCount -ne 1) {
        throw "BF-993 BLOCKED: expected one existing governed matchup read, found $readCount."
    }

    $surface = $dashboard + [Environment]::NewLine + $matchup
    if ($surface -match 'Method = "POST"|submitTransaction|setFaab|AutoFillLineupOptimizer|returnUrl|redirectUrl|javascript:') {
        throw 'BF-993 BLOCKED: Dashboard glance scanability introduced optimizer, write, or open-redirect behavior.'
    }

    Write-Host 'BF-993 DASHBOARD GLANCE SCANABILITY ACCEPTANCE: PASS'
    Write-Host 'Coverage: Matchup, Lineup, and Waiver glance cards now carry concise context while preserving BF-992 role separation and existing read boundaries'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
