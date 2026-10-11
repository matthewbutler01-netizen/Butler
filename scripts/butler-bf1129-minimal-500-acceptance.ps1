Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# BF-1129: test the actual cached-worker HTML response transformation.
# The minimal manager HTTP 500 needs no optional presentation functions.
# These are only in-memory HTTP responses: no league, database or provider IO.
$workerPath = Join-Path $PSScriptRoot 'butler-app-request-worker.ps1'
$cachePath = Join-Path $PSScriptRoot 'butler-app-request-worker-cache.ps1'
$workerSource = [IO.File]::ReadAllText($workerPath, [Text.Encoding]::ASCII)
$cacheSource = [IO.File]::ReadAllText($cachePath, [Text.Encoding]::ASCII)
$match = [regex]::Match($cacheSource, '(?s)\$bodyEncodingReplacement = @''\r?\n(?<block>.*?)\r?\n''@')
if (-not $match.Success) {
    throw 'BF-1129 BLOCKED: HTML presentation stage missing.'
}
$originalLine = '    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($Body)'
if ([regex]::Matches($workerSource, [regex]::Escape($originalLine)).Count -ne 1) {
    throw 'BF-1129 BLOCKED: canonical response body encoding marker not unique.'
}
$stagedSource = $workerSource.Replace($originalLine, $match.Groups['block'].Value.TrimEnd())
$tokens = $null
$parseErrors = $null
$stagedAst = [System.Management.Automation.Language.Parser]::ParseInput(
    $stagedSource, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -ne 0) {
    throw 'BF-1129 BLOCKED: staged public response writer does not parse.'
}
$sendAst = $stagedAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -ceq 'Send-HttpResponse'
}, $true)
if ($null -eq $sendAst) { throw 'BF-1129 BLOCKED: staged HTTP response writer missing.' }
. ([scriptblock]::Create($sendAst.Extent.Text))
$script:bf856RouteTimingEnabled = $false
$script:bf857CoreTimingEnabled = $false

# Make optional presentation unavailable. Successful minimal fallback must
# not depend on either helper. Ordinary HTML must still require both.
function ConvertTo-ButlerUserFacingHtml {
    param([string]$Html)
    throw 'BF1129_PRESENTATION_DEPENDENCY_CALLED'
}
function Add-ButlerAccessibility {
    param([string]$Html)
    throw 'BF1129_ACCESSIBILITY_DEPENDENCY_CALLED'
}

$originalDiagnostics = $env:BUTLER_BF890_ACCEPTANCE_DIAGNOSTICS
$env:BUTLER_BF890_ACCEPTANCE_DIAGNOSTICS = '0'
try {
    foreach ($case in @(
        @{ Code = 500; Layer = 'PUBLIC_WORKER'; Bypass = $true; Expect = 'pass' },
        @{ Code = 500; Layer = 'CORE_READ'; Bypass = $true; Expect = 'pass' },
        @{ Code = 500; Layer = 'CORE_HTTP'; Bypass = $true; Expect = 'blocked' },
        @{ Code = 500; Layer = 'PUBLIC_WORKER'; Bypass = $false; Expect = 'blocked' },
        @{ Code = 200; Layer = ''; Bypass = $true; Expect = 'blocked' },
        @{ Code = 503; Layer = 'PUBLIC_WORKER'; Bypass = $false; Expect = 'pass' }
    )) {
        $buffer = [IO.MemoryStream]::new()
        try {
            $gotFailure = $false
            try {
                Send-HttpResponse -Stream $buffer -StatusCode $case.Code `
                    -StatusText 'Synthetic test' -ContentType 'text/html; charset=utf-8' `
                    -Body '<!doctype html><html lang="en"><body>Safe synthetic page</body></html>' `
                    -FailureLayer $case.Layer -SkipOptionalHtmlPresentation:$case.Bypass
            }
            catch {
                $gotFailure = $_.Exception.Message -match 'BF1129_.*_DEPENDENCY_CALLED'
                if (-not $gotFailure) { throw }
            }
            if ($case.Expect -ceq 'blocked' -and (-not $gotFailure -or $buffer.Length -ne 0)) {
                throw 'BF-1129 BLOCKED: ordinary HTML unexpectedly skipped presentation.'
            }
            if ($case.Expect -ceq 'pass') {
                $wire = [Text.Encoding]::UTF8.GetString($buffer.ToArray())
                $wantStatus = 'HTTP/1.1 ' + $case.Code
                if ($gotFailure -or -not $wire.StartsWith($wantStatus, [StringComparison]::Ordinal) -or
                    $wire.IndexOf('Safe synthetic page', [StringComparison]::Ordinal) -lt 0 -or
                    $wire.IndexOf('Content-Length:', [StringComparison]::Ordinal) -lt 0 -or
                    $wire.IndexOf('Cache-Control: no-store', [StringComparison]::Ordinal) -lt 0 -or
                    $wire.IndexOf('X-Butler-Failure-Layer: ' + $case.Layer, [StringComparison]::Ordinal) -lt 0) {
                    throw 'BF-1129 BLOCKED: independent safe HTML error lost complete HTTP response.'
                }
            }
        }
        finally { $buffer.Dispose() }
    }
}
finally {
    $env:BUTLER_BF890_ACCEPTANCE_DIAGNOSTICS = $originalDiagnostics
}

Write-Host 'BF-1129 PASS: emergency HTTP 500/503 remains independent, ordinary HTML still formats.'
