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
    $expected = @(
        [pscustomobject]@{ Label = 'Refresh projection'; Marker = 'href=`"/team/autofill`">Refresh projection</a>' },
        [pscustomobject]@{ Label = 'Back to Dashboard'; Marker = 'href=`"/`">Back to Dashboard</a>' },
        [pscustomobject]@{ Label = 'Back to Matchup'; Marker = 'href=`"/matchup`">Back to Matchup</a>' },
        [pscustomobject]@{ Label = 'Back to My Team'; Marker = 'href=`"/team`">Back to My Team</a>' }
    )

    $positions = @()
    foreach ($item in $expected) {
        $count = [regex]::Matches($function, [regex]::Escape($item.Marker)).Count
        if ($count -ne 1) {
            throw "BF-1011 expected exactly one $($item.Label) footer action, found $count."
        }
        $positions += $function.IndexOf($item.Marker, [System.StringComparison]::Ordinal)
    }

    for ($i = 1; $i -lt $positions.Count; $i++) {
        if ($positions[$i] -le $positions[$i - 1]) {
            throw 'BF-1011 canonical footer action order was not preserved.'
        }
    }

    if ([regex]::Matches($function, [regex]::Escape('Back to Matchup')).Count -ne 1) {
        throw 'BF-1011 duplicate Back to Matchup label remains in Lineup Review.'
    }

    Write-Host 'BF-1011 LINEUP FOOTER RETURN DEDUP: PASS'
}
finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
