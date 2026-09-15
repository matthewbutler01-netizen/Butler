param(
    [string]$RecordPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$outputDir = [IO.Path]::GetFullPath((Join-Path $repoRoot 'release-output'))

function Resolve-GitExecutable {
    $gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($null -eq $gitCommand) {
        $gitCommand = Get-Command git -ErrorAction SilentlyContinue
    }
    if ($null -eq $gitCommand) {
        throw 'BF-778 BLOCKED: Git executable is unavailable.'
    }
    return $gitCommand.Source
}

if ([string]::IsNullOrWhiteSpace($RecordPath)) {
    $git = Resolve-GitExecutable
    Push-Location $repoRoot
    try {
        $headSha = (& $git 'rev-parse' '--verify' 'HEAD^{commit}' 2>&1 | ForEach-Object { "$_" }) -join "`n"
        $gitExit = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
    if ($gitExit -ne 0 -or $headSha.Trim() -notmatch '^[0-9a-fA-F]{40}$') {
        throw 'BF-778 BLOCKED: HEAD did not resolve to one exact commit SHA.'
    }
    $headSha = $headSha.Trim().ToLowerInvariant()
    $headShortSha = $headSha.Substring(0, 8)
    $resolvedRecordPath = Join-Path $outputDir "Butler-release-$headShortSha.verified.txt"
}
else {
    if ([IO.Path]::IsPathRooted($RecordPath)) {
        $resolvedRecordPath = [IO.Path]::GetFullPath($RecordPath)
    }
    else {
        $resolvedRecordPath = [IO.Path]::GetFullPath((Join-Path $repoRoot $RecordPath))
    }
}

$recordDirectory = [IO.Path]::GetFullPath((Split-Path -Parent $resolvedRecordPath))
if (-not [StringComparer]::OrdinalIgnoreCase.Equals($recordDirectory.TrimEnd('\'), $outputDir.TrimEnd('\'))) {
    throw 'BF-778 BLOCKED: verification record must reside directly under release-output.'
}
if (-not (Test-Path -LiteralPath $resolvedRecordPath -PathType Leaf)) {
    throw "BF-778 BLOCKED: release verification record is missing at $resolvedRecordPath"
}

$recordLines = @(Get-Content -LiteralPath $resolvedRecordPath -Encoding ASCII)
if ($recordLines.Count -ne 9 -or $recordLines[0] -cne 'BUTLER_RELEASE_VERIFICATION_V1') {
    throw 'BF-778 BLOCKED: release verification record schema/version is invalid.'
}

$requiredKeys = @(
    'commit',
    'short_commit',
    'runtime_artifact',
    'runtime_sha256',
    'runtime_manifest',
    'release_acceptance',
    'runtime_boundary',
    'verification_boundary'
)
$record = @{}
for ($i = 1; $i -lt $recordLines.Count; $i++) {
    $line = $recordLines[$i]
    if ($line -notmatch '^([a-z_]+)=(.+)$') {
        throw "BF-778 BLOCKED: malformed verification record line: $line"
    }
    $key = $matches[1]
    $value = $matches[2]
    if ($requiredKeys -cnotcontains $key) {
        throw "BF-778 BLOCKED: unexpected verification record key: $key"
    }
    if ($record.ContainsKey($key)) {
        throw "BF-778 BLOCKED: duplicate verification record key: $key"
    }
    $record[$key] = $value
}
foreach ($key in $requiredKeys) {
    if (-not $record.ContainsKey($key)) {
        throw "BF-778 BLOCKED: verification record is missing required key: $key"
    }
}

$commit = [string]$record['commit']
$shortCommit = [string]$record['short_commit']
$artifactName = [string]$record['runtime_artifact']
$recordHash = [string]$record['runtime_sha256']
$manifestName = [string]$record['runtime_manifest']

if ($commit -notmatch '^[0-9a-f]{40}$') {
    throw 'BF-778 BLOCKED: verification record commit is not canonical lowercase 40-hex.'
}
if ($shortCommit -notmatch '^[0-9a-f]{8}$' -or $shortCommit -cne $commit.Substring(0, 8)) {
    throw 'BF-778 BLOCKED: verification record short commit does not match the full commit.'
}
if ($recordHash -notmatch '^[0-9a-f]{64}$') {
    throw 'BF-778 BLOCKED: verification record runtime SHA-256 is not canonical lowercase 64-hex.'
}
if ($artifactName -cne "Butler-runtime-$shortCommit.zip") {
    throw 'BF-778 BLOCKED: verification record runtime artifact name is inconsistent with its commit.'
}
if ($manifestName -cne "Butler-runtime-$shortCommit.manifest.txt") {
    throw 'BF-778 BLOCKED: verification record runtime manifest name is inconsistent with its commit.'
}
if ((Split-Path -Leaf $resolvedRecordPath) -cne "Butler-release-$shortCommit.verified.txt") {
    throw 'BF-778 BLOCKED: verification record filename is inconsistent with its commit.'
}
if ([string]$record['release_acceptance'] -cne 'BF-776_PASS') {
    throw 'BF-778 BLOCKED: verification record does not attest BF-776 PASS.'
}
if ([string]$record['runtime_boundary'] -cne 'CODE_PLUS_PREBUILT_READ_RUNTIME_NO_RUNTIME_DATA') {
    throw 'BF-778 BLOCKED: verification record runtime boundary is invalid.'
}
if ([string]$record['verification_boundary'] -cne 'RELEASE_METADATA_ONLY_NO_RUNTIME_DATA') {
    throw 'BF-778 BLOCKED: verification record metadata boundary is invalid.'
}

$artifactPath = Join-Path $recordDirectory $artifactName
$checksumPath = $artifactPath + '.sha256'
$manifestPath = Join-Path $recordDirectory $manifestName
foreach ($requiredPath in @($artifactPath, $checksumPath, $manifestPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "BF-778 BLOCKED: referenced release evidence is missing at $requiredPath"
    }
}

$actualHash = (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -cne $recordHash) {
    throw 'BF-778 BLOCKED: runtime artifact SHA-256 does not match the BF-777 verification record.'
}

$checksumText = (Get-Content -LiteralPath $checksumPath -Raw -Encoding ASCII).Trim()
if ($checksumText -notmatch '^([0-9a-fA-F]{64})\s+(.+)$') {
    throw 'BF-778 BLOCKED: runtime checksum sidecar format is invalid.'
}
$sidecarHash = $matches[1].ToLowerInvariant()
$sidecarArtifact = $matches[2].Trim()
if ($sidecarHash -cne $actualHash -or $sidecarArtifact -cne $artifactName) {
    throw 'BF-778 BLOCKED: runtime checksum sidecar does not match the verified artifact.'
}

$manifestLines = @(Get-Content -LiteralPath $manifestPath -Encoding ASCII)
foreach ($requiredManifestLine in @(
    'BUTLER_RUNTIME_RELEASE_MANIFEST_V1',
    "commit=$commit",
    "artifact=$artifactName",
    "sha256=$actualHash",
    'boundary=CODE_PLUS_PREBUILT_READ_RUNTIME_NO_RUNTIME_DATA'
)) {
    if ($manifestLines -cnotcontains $requiredManifestLine) {
        throw "BF-778 BLOCKED: runtime manifest is missing required binding: $requiredManifestLine"
    }
}

Write-Host 'Butler saved release verification check (BF-778)'
Write-Host "Record: $resolvedRecordPath"
Write-Host "Commit: $commit"
Write-Host "Artifact: $artifactName"
Write-Host "Runtime SHA-256: $actualHash"
Write-Host 'Boundary: OFFLINE_RELEASE_EVIDENCE_READ_ONLY_NO_RUNTIME_DATA'
Write-Host 'BF-778 RELEASE VERIFICATION CHECK: PASS'
