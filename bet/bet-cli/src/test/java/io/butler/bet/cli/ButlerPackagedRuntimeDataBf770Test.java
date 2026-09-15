package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPackagedRuntimeDataBf770Test {

    @Test
    void productLauncherMovesRuntimeDataOutsideSourceAndFailsClosedOnLegacyState() throws Exception {
        String app = source("scripts/butler-app.ps1");

        assertTrue(app.contains("Join-Path $configDir \"data\""));
        assertTrue(app.contains("BUTLER_APP_DATA_DIR"));
        assertTrue(app.contains("[IO.Path]::IsPathRooted($configured)"));
        assertTrue(app.contains("runtime data directory must be outside the source/package tree"));
        assertTrue(app.contains("Join-Path $repoRoot \"bet\\bet-cli\\butler.db\""));
        assertTrue(app.contains("legacy Butler database remains in the source tree"));
        assertTrue(app.contains("governed Butler runtime database is missing"));
        assertTrue(app.contains("$env:BUTLER_APP_DATA_DIR = $dataDir"));
        assertTrue(app.contains("Write-Host \"Data: $dataDir\""));

        assertFalse(app.contains("Copy-Item -LiteralPath $legacyDatabasePath"));
        assertFalse(app.contains("Remove-Item -LiteralPath $legacyDatabasePath"));
    }

    @Test
    void bothJavaExecutionPathsRequireGovernedExternalDataDir() throws Exception {
        String direct = source("scripts/butler-direct-java-dispatch.ps1");
        String persistent = source("scripts/butler-persistent-core-worker.ps1");

        for (String text : new String[]{direct, persistent}) {
            assertTrue(text.contains("$dataDir = [string]$env:BUTLER_APP_DATA_DIR"));
            assertTrue(text.contains("[IO.Path]::IsPathRooted($dataDir)"));
            assertTrue(text.contains("$workingDir = [IO.Path]::GetFullPath($dataDir)"));
            assertTrue(text.contains("BF-771 BLOCKED"));
            assertTrue(text.contains("governed Butler app data directory is unavailable"));
            assertFalse(text.contains("BUTLER_APP_REPO_ROOT"));
            assertFalse(text.contains("Join-Path $repoRoot 'bet\\bet-cli'"));
        }
        assertTrue(direct.contains("Push-Location $workingDir"));
        assertTrue(persistent.contains("$start.WorkingDirectory = $workingDir"));
    }

    @Test
    void migrationIsExplicitNonDestructiveAndHashVerified() throws Exception {
        String migration = source("scripts/butler-migrate-runtime-data.ps1");

        assertTrue(migration.contains("Join-Path $configDir 'data'"));
        assertTrue(migration.contains("running-port-*.txt"));
        assertTrue(migration.contains("@('-wal', '-shm', '-journal')"));
        assertTrue(migration.contains("SQLite format 3`0"));
        assertTrue(migration.contains("$sourceHashBefore = (Get-FileHash"));
        assertTrue(migration.contains("$sourceHashAfter = (Get-FileHash"));
        assertTrue(migration.contains("$copyHash = (Get-FileHash"));
        assertTrue(migration.contains("$sourceHashBefore -cne $sourceHashAfter"));
        assertTrue(migration.contains("$sourceHashBefore -cne $copyHash"));
        assertTrue(migration.contains("Move-Item -LiteralPath $tempPath -Destination $targetPath"));
        assertTrue(migration.contains("Legacy source retained unchanged for rollback."));
        assertTrue(migration.contains("BF-770 RUNTIME DATA MIGRATION: PASS"));

        assertFalse(migration.contains("Remove-Item -LiteralPath $sourcePath"));
        assertFalse(migration.contains("Move-Item -LiteralPath $sourcePath"));
    }

    @Test
    void packagedAcceptanceLaunchesExtractedCurrentArtifactAndReusesSecurityGate() throws Exception {
        String packaged = source("scripts/butler-packaged-launch-acceptance.ps1");

        assertTrue(packaged.contains("gitCommand.Source 'rev-parse' '--short=8' 'HEAD'"));
        assertTrue(packaged.contains("Butler-source-{0}.zip"));
        assertTrue(packaged.contains("Get-FileHash -LiteralPath $SourceZip -Algorithm SHA256"));
        assertTrue(packaged.contains("Expand-Archive -LiteralPath $SourceZip -DestinationPath $tempRoot"));
        assertTrue(packaged.contains("Butler-bf770-"));
        assertTrue(packaged.contains("$start.EnvironmentVariables['BUTLER_APP_DATA_DIR'] = $dataDir"));
        assertTrue(packaged.contains("butler-release-security-check.ps1"));
        assertTrue(packaged.contains("& $securityCheck -BaseUrl"));
        assertTrue(packaged.contains("EXTRACTED_PACKAGE_CODE_ONLY; SQLITE_RUNTIME_DATA_EXTERNAL"));
        assertTrue(packaged.contains("BF-770 PACKAGED LAUNCH: PASS"));
        assertTrue(packaged.contains("& $taskkill /PID $Process.Id /T /F"));

        assertFalse(packaged.contains("POST /refresh"));
        assertFalse(packaged.contains("Copy-Item -LiteralPath $databasePath"));
    }

    @Test
    void bf770WindowsSourcesRemainAsciiOnly() throws Exception {
        assertAscii(source("scripts/butler-app.ps1"));
        assertAscii(source("scripts/butler-direct-java-dispatch.ps1"));
        assertAscii(source("scripts/butler-persistent-core-worker.ps1"));
        assertAscii(source("scripts/butler-migrate-runtime-data.ps1"));
        assertAscii(source("scripts/butler-packaged-launch-acceptance.ps1"));
    }

    private static void assertAscii(String text) {
        byte[] encoded = text.getBytes(StandardCharsets.US_ASCII);
        assertEquals(text, new String(encoded, StandardCharsets.US_ASCII));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-770 test could not locate " + relativePath);
    }
}
