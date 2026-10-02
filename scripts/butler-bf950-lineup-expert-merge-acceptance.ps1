Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$acceptance = Join-Path $PSScriptRoot 'butler-bf943-lineup-render-acceptance.ps1'
if (-not (Test-Path -LiteralPath $acceptance -PathType Leaf)) {
    throw "BF-950 BLOCKED: shared lineup render acceptance not found at $acceptance"
}

$lines = @(& $acceptance *>&1)
$text = ($lines | ForEach-Object { "$_" }) -join "`n"

if ($text -notmatch 'BF-950 Lineup Review expert/proposal merge applied\.' -or
    $text -notmatch 'BF-943 LINEUP RENDER ACCEPTANCE: PASS') {
    throw "BF-950 BLOCKED: staged expert/proposal merge or shared render acceptance did not complete as expected.`n$text"
}

$lines | ForEach-Object { Write-Host "$_" }
Write-Host 'BF-950 LINEUP REVIEW EXPERT MERGE ACCEPTANCE: PASS'
