param(
    [ValidateRange(1, 999)]
    [int]$MinimumMajor = 25,

    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$java = $null
if (-not [string]::IsNullOrWhiteSpace([string]$env:JAVA_HOME)) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        $java = $candidate
    }
}

if ([string]::IsNullOrWhiteSpace([string]$java)) {
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($null -ne $javaCommand) {
        $java = $javaCommand.Source
    }
}

if ([string]::IsNullOrWhiteSpace([string]$java)) {
    throw "BF-782 BLOCKED: Java $MinimumMajor or newer is required but java.exe is unavailable."
}

$previousPreference = $ErrorActionPreference
$versionLines = $null
$javaExit = $null
try {
    $ErrorActionPreference = 'Continue'
    $versionLines = @(& $java '-version' 2>&1)
    $javaExit = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previousPreference
}

$versionText = ($versionLines | ForEach-Object { "$_" }) -join "`n"
if ($javaExit -ne 0) {
    throw "BF-782 BLOCKED: Java version probe failed with exit code $javaExit at $java."
}

$versionMatch = [regex]::Match(
    $versionText,
    'version\s+"(?<version>[0-9]+(?:\.[0-9]+)*)"',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
)
if (-not $versionMatch.Success) {
    throw "BF-782 BLOCKED: Java version output could not be parsed at $java."
}

$versionParts = $versionMatch.Groups['version'].Value.Split('.')
$major = 0
if (-not [int]::TryParse($versionParts[0], [ref]$major)) {
    throw "BF-782 BLOCKED: Java major version could not be parsed at $java."
}
if ($major -eq 1 -and $versionParts.Length -gt 1) {
    if (-not [int]::TryParse($versionParts[1], [ref]$major)) {
        throw "BF-782 BLOCKED: legacy Java major version could not be parsed at $java."
    }
}

if ($major -lt $MinimumMajor) {
    throw "BF-782 BLOCKED: Java $MinimumMajor or newer is required; resolved Java major version $major at $java."
}

if ($PassThru) {
    [pscustomobject]@{ Executable = $java; Major = $major; Version = $versionMatch.Groups['version'].Value }
}
