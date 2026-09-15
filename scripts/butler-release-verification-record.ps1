Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($env:BUTLER_BF776_ACCEPTANCE_VERIFIED -cne '1') {
    throw 'BF-777 BLOCKED: release verification record may only be written after successful BF-776 acceptance.'
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$outputDir = Join-Path $repoRoot 'release-output'

$gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
if ($null -eq $gitCommand) {
    $gitCommand = Get-Command git -ErrorAction SilentlyContinue
}
if ($null -eq $gitCommand) {
    throw 'BF-777 BLOCKED: Git executable is unavailable.'
}
$git = $gitCommand.Source

Push-Location $repoRoot
try {
    $fullSha = (& $git 'rev-parse' '--verify' 'HEAD^{commit}' 2>&1 | ForEach-Object { "$_" }) -join "`n"
    $gitExit = $LASTEXITCODE
}
finally {
    Pop-Location
}
if ($gitExit -ne 0 -or $fullSha.Trim() -notmatch '^[0-9a-fA-F]{40}$') {
    throw 'BF-777 BLOCKED: HEAD did not resolve to one exact commit SHA.'
}
$fullSha = $fullSha.Trim().ToLowerInvariant()
$shortSha = $fullSha.Substring(0, 8)

$artifactName = "Butler-runtime-$shortSha.zip"
$manifestName = "Butler-runtime-$shortSha.manifest.txt"
$recordName = "Butler-release-$shortSha.verified.txt"
$artifactPath = Join-Path $outputDir $artifactName
$checksumPath = $artifactPath + '.sha256'
$manifestPath = Join-Path $outputDir $manifestName
$recordPath = Join-Path $outputDir $recordName

foreach ($requiredPath in @($artifactPath, $checksumPath, $manifestPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "BF-777 BLOCKED: required current-HEAD release evidence is missing at $requiredPath"
    }
}

$checksumText = (Get-Content -LiteralPath $checksumPath -Raw -Encoding ASCII).Trim()
if ($checksumText -notmatch '^([0-9a-fA-F]{64})\s+(.+)$') {
    throw 'BF-777 BLOCKED: runtime checksum sidecar format is invalid.'
}
$expectedHash = $matches[1].ToLowerInvariant()
$sidecarArtifact = $matches[2].Trim()
if ($sidecarArtifact -cne $artifactName) {
    throw "BF-777 BLOCKED: runtime checksum sidecar names $sidecarArtifact instead of $artifactName"
}
$actualHash = (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -cne $expectedHash) {
    throw 'BF-777 BLOCKED: runtime artifact SHA-256 does not match its sidecar.'
}

$manifestLines = @(Get-Content -LiteralPath $manifestPath -Encoding ASCII)
foreach ($requiredManifestLine in @(
    'BUTLER_RUNTIME_RELEASE_MANIFEST_V1',
    "commit=$fullSha",
    "artifact=$artifactName",
    "sha256=$actualHash",
    'boundary=CODE_PLUS_PREBUILT_READ_RUNTIME_NO_RUNTIME_DATA'
)) {
    if ($manifestLines -cnotcontains $requiredManifestLine) {
        throw "BF-777 BLOCKED: runtime manifest is missing required binding: $requiredManifestLine"
    }
}

[IO.Directory]::CreateDirectory($outputDir) | Out-Null
$recordLines = @(
    'BUTLER_RELEASE_VERIFICATION_V1',
    "commit=$fullSha",
    "short_commit=$shortSha",
    "runtime_artifact=$artifactName",
    "runtime_sha256=$actualHash",
    "runtime_manifest=$manifestName",
    'release_acceptance=BF-776_PASS',
    'runtime_boundary=CODE_PLUS_PREBUILT_READ_RUNTIME_NO_RUNTIME_DATA',
    'verification_boundary=RELEASE_METADATA_ONLY_NO_RUNTIME_DATA'
)
$tempPath = $recordPath + '.tmp'
try {
    [IO.File]::WriteAllText($tempPath, (($recordLines -join "`r`n") + "`r`n"), [Text.Encoding]::ASCII)
    Move-Item -LiteralPath $tempPath -Destination $recordPath -Force
}
finally {
    if (Test-Path -LiteralPath $tempPath) {
        Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue
    }
}

Write-Host 'Butler release verification record (BF-777)'
Write-Host "Record: $recordPath"
Write-Host "Commit: $fullSha"
Write-Host "Runtime SHA-256: $actualHash"
Write-Host 'Boundary: RELEASE_METADATA_ONLY_NO_RUNTIME_DATA'
Write-Host 'BF-777 RELEASE VERIFICATION RECORD: PASS'
