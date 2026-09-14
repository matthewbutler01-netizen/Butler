param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$DashboardPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DashboardPath -PathType Leaf)) {
    throw "BF-743 BLOCKED: staged dashboard not found at $DashboardPath"
}
if ([string]$env:BUTLER_APP_WORKER_DATABASE_WARMUP -ceq '0') {
    return
}

$startupOriginal = @'
try {
    [void](Start-Bf740PersistentCoreWorker)
    $listener.Start()
'@

$startupReplacement = @'
try {
    # BF-743: production shared workers warm only the database schema initialization proof before READY.
    # The environment marker belongs to this isolated child dashboard process and is inherited only by its worker.
    $env:BUTLER_READ_ONLY_WORKER_PREINITIALIZE_DATABASE = '1'
    [void](Start-Bf740PersistentCoreWorker)
    $listener.Start()
'@

$text = [System.IO.File]::ReadAllText($DashboardPath)
$matches = [regex]::Matches($text, [regex]::Escape($startupOriginal)).Count
if ($matches -ne 1) {
    throw "BF-743 BLOCKED: expected exactly one shared-worker startup contract, found $matches."
}

$text = $text.Replace($startupOriginal, $startupReplacement)
if (-not $text.Contains("BUTLER_READ_ONLY_WORKER_PREINITIALIZE_DATABASE = '1'")) {
    throw 'BF-743 BLOCKED: staged dashboard is missing the production worker database warmup marker.'
}
if ($text.Contains($startupOriginal)) {
    throw 'BF-743 BLOCKED: staged dashboard still contains the untransformed shared-worker startup contract.'
}

[System.IO.File]::WriteAllText($DashboardPath, $text, [System.Text.UTF8Encoding]::new($false))
