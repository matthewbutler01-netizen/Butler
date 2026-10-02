Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$acceptance = Join-Path $PSScriptRoot 'butler-bf943-lineup-render-acceptance.ps1'
if (-not (Test-Path -LiteralPath $acceptance -PathType Leaf)) {
    throw "BF-951 BLOCKED: shared lineup render acceptance not found at $acceptance"
}

$lines = @(& $acceptance *>&1)
$text = ($lines | ForEach-Object { "$_" }) -join "`n"

if ($text -notmatch 'BF-951 Lineup Review expert/comparison merge applied\.' -or
    $text -notmatch 'BF-943 LINEUP RENDER ACCEPTANCE: PASS') {
    throw "BF-951 BLOCKED: staged expert/comparison merge or shared render acceptance did not complete as expected.`n$text"
}

$lines | ForEach-Object { Write-Host "$_" }
Write-Host 'BF-951 LINEUP REVIEW EXPERT COMPARISON ACCEPTANCE: PASS'
