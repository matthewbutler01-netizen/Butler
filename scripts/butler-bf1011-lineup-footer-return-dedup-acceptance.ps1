Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Join-Path ([IO.Path]::GetTempPath()) ('Butler-bf1011-footer-' + [guid]::NewGuid().ToString('N'))
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
    if (@($errors).Count -ne 0) {
        $summary = (@($errors) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }) -join '; '
        throw "BF-1011 staged core failed parse: $summary"
    }

    $functions = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'ConvertTo-AutoFillHtml'
    }, $true))
    if ($functions.Count -ne 1) {
        throw "BF-1011 expected one ConvertTo-AutoFillHtml function, found $($functions.Count)."
    }

    $function = $functions[0].Extent.Text
    $buttonRowStart = '<div class=`"button-row`">'
    $buttonRowEnd = '</div>'
    $refresh = '<a class=`"btn btn-secondary`" href=`"/team/autofill`">Refresh projection</a>'
    $dashboard = '<a class=`"btn btn-secondary`" href=`"/`">Back to Dashboard</a>'
    $matchup = '<a class=`"btn btn-secondary`" href=`"/matchup`">Back to Matchup</a>'
    $team = '<a class=`"btn btn-secondary`" href=`"/team`">Back to My Team</a>'

    $refreshCount = [regex]::Matches($function, [regex]::Escape($refresh)).Count
    if ($refreshCount -ne 1) {
        throw "BF-1011 expected one completed Refresh projection action, found $refreshCount."
    }

    $refreshIndex = $function.IndexOf($refresh, [System.StringComparison]::Ordinal)
    $footerStart = $function.LastIndexOf($buttonRowStart, $refreshIndex, [System.StringComparison]::Ordinal)
    $footerEnd = $function.IndexOf($buttonRowEnd, $refreshIndex, [System.StringComparison]::Ordinal)
    if ($footerStart -lt 0 -or $footerEnd -lt 0 -or $footerEnd -le $footerStart) {
        throw 'BF-1011 completed Lineup Review footer could not be isolated.'
    }
    $footerEnd += $buttonRowEnd.Length
    $footer = $function.Substring($footerStart, $footerEnd - $footerStart)

    $canonical = $buttonRowStart + $refresh + $dashboard + $matchup + $team + $buttonRowEnd
    if ($footer -cne $canonical) {
        throw "BF-1011 completed Lineup Review footer is not canonical.`nACTUAL:`n$footer"
    }

    if ([regex]::Matches($footer, [regex]::Escape('Back to Matchup')).Count -ne 1 -or
        [regex]::Matches($footer, [regex]::Escape('Back to My Team')).Count -ne 1) {
        throw 'BF-1011 completed footer still contains duplicate destination labels.'
    }

    Write-Host 'BF-1011 LINEUP FOOTER RETURN DEDUP: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
