Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$acceptance = Join-Path $PSScriptRoot 'butler-bf943-lineup-render-acceptance.ps1'
if (-not (Test-Path -LiteralPath $acceptance -PathType Leaf)) {
    throw "BF-953 BLOCKED: shared lineup render acceptance not found at $acceptance"
}

$lines = @(& $acceptance *>&1)
$text = ($lines | ForEach-Object { "$_" }) -join "`n"

if ($text -notmatch 'BF-953 Lineup Review hold/expert merge applied\.' -or
    $text -notmatch 'BF-943 LINEUP RENDER ACCEPTANCE: PASS') {
    throw "BF-953 BLOCKED: staged hold/expert merge or shared render acceptance did not complete as expected.`n$text"
}

$lines | ForEach-Object { Write-Host "$_" }
Write-Host 'BF-953 LINEUP REVIEW HOLD EXPERT ACCEPTANCE: PASS'
