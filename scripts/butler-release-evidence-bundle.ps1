param(
    [string]$RecordPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$outputDir = [IO.Path]::GetFullPath((Join-Path $repoRoot 'release-output'))
$verifyScript = Join-Path $scriptDir 'butler-release-verification-check.ps1'
if (-not (Test-Path -LiteralPath $verifyScript -PathType Leaf)) {
    throw "BF-787 BLOCKED: BF-778 verifier not found at $verifyScript"
}

function Resolve-GitExecutable {
    $gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($null -eq $gitCommand) {
        $gitCommand = Get-Command git -ErrorAction SilentlyContinue
    }
    if ($null -eq $gitCommand) {
        throw 'BF-787 BLOCKED: Git executable is unavailable.'
    }
    return $gitCommand.Source
}

if ([string]::IsNullOrWhiteSpace($RecordPath)) {
    & $verifyScript

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
        throw 'BF-787 BLOCKED: HEAD did not resolve to one exact commit SHA.'
    }
    $shortCommit = $headSha.Trim().ToLowerInvariant().Substring(0, 8)
    $resolvedRecordPath = Join-Path $outputDir "Butler-release-$shortCommit.verified.txt"
}
else {
    & $verifyScript -RecordPath $RecordPath

    if ([IO.Path]::IsPathRooted($RecordPath)) {
        $resolvedRecordPath = [IO.Path]::GetFullPath($RecordPath)
    }
    else {
        $resolvedRecordPath = [IO.Path]::GetFullPath((Join-Path $repoRoot $RecordPath))
    }
}

$recordDirectory = [IO.Path]::GetFullPath((Split-Path -Parent $resolvedRecordPath))
if (-not [StringComparer]::OrdinalIgnoreCase.Equals($recordDirectory.TrimEnd('\'), $outputDir.TrimEnd('\'))) {
    throw 'BF-787 BLOCKED: verified release record must reside directly under release-output.'
}

$recordLines = @(Get-Content -LiteralPath $resolvedRecordPath -Encoding ASCII)
$record = @{}
for ($i = 1; $i -lt $recordLines.Count; $i++) {
    if ($recordLines[$i] -match '^([a-z0-9_]+)=(.+)$') {
        $record[$matches[1]] = $matches[2]
    }
}
foreach ($requiredKey in @('short_commit', 'runtime_artifact', 'runtime_manifest')) {
    if (-not $record.ContainsKey($requiredKey)) {
        throw "BF-787 BLOCKED: verified release record is missing required key: $requiredKey"
    }
}

$shortCommit = [string]$record['short_commit']
$artifactName = [string]$record['runtime_artifact']
$checksumName = $artifactName + '.sha256'
$manifestName = [string]$record['runtime_manifest']
$recordName = Split-Path -Leaf $resolvedRecordPath
$approvedNames = @($artifactName, $checksumName, $manifestName, $recordName)
$approvedPaths = @(
    (Join-Path $outputDir $artifactName),
    (Join-Path $outputDir $checksumName),
    (Join-Path $outputDir $manifestName),
    $resolvedRecordPath
)

for ($i = 0; $i -lt $approvedPaths.Count; $i++) {
    if (-not (Test-Path -LiteralPath $approvedPaths[$i] -PathType Leaf)) {
        throw "BF-787 BLOCKED: verified release evidence is missing at $($approvedPaths[$i])"
    }
}

$archiveName = "Butler-release-evidence-$shortCommit.zip"
$archivePath = Join-Path $outputDir $archiveName
$archiveChecksumPath = $archivePath + '.sha256'
$token = [Guid]::NewGuid().ToString('N')
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("Butler-bf787-$token")
$stageDir = Join-Path $tempRoot 'evidence'
$tempArchivePath = Join-Path $outputDir ("Butler-release-evidence-$shortCommit.tmp-$token.zip")
$tempChecksumPath = $archiveChecksumPath + ".tmp-$token"

try {
    [IO.Directory]::CreateDirectory($stageDir) | Out-Null

    for ($i = 0; $i -lt $approvedPaths.Count; $i++) {
        Copy-Item -LiteralPath $approvedPaths[$i] -Destination (Join-Path $stageDir $approvedNames[$i])
    }

    $stagedFiles = @(Get-ChildItem -LiteralPath $stageDir -File)
    if ($stagedFiles.Count -ne 4) {
        throw "BF-787 BLOCKED: evidence staging contains $($stagedFiles.Count) files instead of exactly 4."
    }
    foreach ($requiredName in $approvedNames) {
        if ($stagedFiles.Name -cnotcontains $requiredName) {
            throw "BF-787 BLOCKED: evidence staging is missing approved file: $requiredName"
        }
    }

    Compress-Archive -Path (Join-Path $stageDir '*') -DestinationPath $tempArchivePath -CompressionLevel Optimal -Force

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($tempArchivePath)
    try {
        $entryNames = @($zip.Entries | Where-Object { -not [string]::IsNullOrEmpty($_.Name) } | ForEach-Object { $_.FullName })
        if ($entryNames.Count -ne 4) {
            throw "BF-787 BLOCKED: evidence archive contains $($entryNames.Count) files instead of exactly 4."
        }
        foreach ($requiredName in $approvedNames) {
            if ($entryNames -cnotcontains $requiredName) {
                throw "BF-787 BLOCKED: evidence archive is missing approved file: $requiredName"
            }
        }
        foreach ($entryName in $entryNames) {
            if ($approvedNames -cnotcontains $entryName) {
                throw "BF-787 BLOCKED: evidence archive contains unexpected file: $entryName"
            }
        }
    }
    finally {
        $zip.Dispose()
    }

    $archiveHash = (Get-FileHash -LiteralPath $tempArchivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($tempChecksumPath, ("{0}  {1}`r`n" -f $archiveHash, $archiveName), [Text.Encoding]::ASCII)

    Move-Item -LiteralPath $tempArchivePath -Destination $archivePath -Force
    Move-Item -LiteralPath $tempChecksumPath -Destination $archiveChecksumPath -Force

    Write-Host 'Butler portable verified release evidence (BF-787)'
    Write-Host "Record: $resolvedRecordPath"
    Write-Host "Archive: $archivePath"
    Write-Host "SHA-256: $archiveHash"
    Write-Host 'Contents: 4 exact BF-778-verified release evidence files'
    Write-Host 'Boundary: VERIFIED_RELEASE_EVIDENCE_ONLY_NO_RUNTIME_DATA_NO_PUBLICATION'
    Write-Host 'BF-787 RELEASE EVIDENCE ARCHIVE: PASS'
}
finally {
    if (Test-Path -LiteralPath $tempArchivePath) {
        Remove-Item -LiteralPath $tempArchivePath -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $tempChecksumPath) {
        Remove-Item -LiteralPath $tempChecksumPath -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
