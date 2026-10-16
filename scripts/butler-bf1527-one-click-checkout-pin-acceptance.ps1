# BF-1527 isolated Windows acceptance for beta candidate disk-HEAD proof.
# Executes only the extracted pure source-check function; NEVER the launcher.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$source=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'butler-v04-beta-one-click.ps1'))
$start=$source.IndexOf("# BF-1527: verify the checkout", [StringComparison]::Ordinal)
$finish=$source.IndexOf("try {\n    Write-Host 'BUTLER v0.4 - OPEN BETA (READ ONLY)'", $start, [StringComparison]::Ordinal)
if ($start -lt 0 -or $finish -le $start) {
    throw 'BF-1527 BLOCKED: isolated source pin function boundary not found.'
}
$guard=[scriptblock]::Create($source.Substring($start,$finish-$start))
. $guard
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$head=@(& git -C $repo rev-parse HEAD)
if ($LASTEXITCODE -ne 0 -or $head.Count -ne 1 -or $head[0] -notmatch '^[0-9a-f]{40}$') {
    throw 'BF-1527 BLOCKED: CI test checkout HEAD could not be read.'
}
if ($head[0] -ceq $approvedCandidateCommit) {
    throw 'BF-1527 BLOCKED: fixture must be unapproved before testing mismatch.'
}
$blocked=$false
try { [void](Assert-BetaCandidateCheckout -Path $repo) }
catch { $blocked=([string]$_.Exception.Message).Contains('BETA CHECKOUT MISMATCH') }
if (-not $blocked) {
    throw 'BF-1527 BLOCKED: mismatching checked-out code was accepted.'
}
$approvedCandidateCommit=$head[0]
$result=Assert-BetaCandidateCheckout -Path $repo
if ($result -cne $head[0]) {
    throw 'BF-1527 BLOCKED: exact checked-out code was not accepted.'
}
$blocked=$false
try {
    [void](Assert-BetaCandidateCheckout -Path (Join-Path $env:TEMP ('not-a-repository-' + [guid]::NewGuid().ToString('N'))))
}
catch { $blocked=([string]$_.Exception.Message).Contains('BETA CHECKOUT MISMATCH') }
if (-not $blocked) {
    throw 'BF-1527 BLOCKED: untrusted missing checkout was accepted.'
}
Write-Host 'BF-1527 ONE-CLICK CHECKOUT PIN ACCEPTANCE: PASS (read-only; no app launch)'
