package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerGovernedRuntimeDataBf771Test {

    @Test
    void javaExecutionWrappersFailClosedWithoutGovernedDataDir() throws Exception {
        String direct = source("scripts/butler-direct-java-dispatch.ps1");
        String persistent = source("scripts/butler-persistent-core-worker.ps1");

        for (String text : new String[]{direct, persistent}) {
            assertTrue(text.contains("$dataDir = [string]$env:BUTLER_APP_DATA_DIR"));
            assertTrue(text.contains("[IO.Path]::IsPathRooted($dataDir)"));
            assertTrue(text.contains("$workingDir = [IO.Path]::GetFullPath($dataDir)"));
            assertTrue(text.contains("BF-771 BLOCKED"));
            assertTrue(text.contains("Test-Path -LiteralPath $workingDir -PathType Container"));
            assertFalse(text.contains("BUTLER_APP_REPO_ROOT"));
            assertFalse(text.contains("Join-Path $repoRoot 'bet\\bet-cli'"));
        }

        assertTrue(direct.contains("Push-Location $workingDir"));
        assertTrue(direct.contains("finally {\n    Pop-Location\n}"));
        assertTrue(persistent.contains("$start.WorkingDirectory = $workingDir"));
    }

    @Test
    void legacyDatabaseRemainsMigrationOnlyRollbackSource() throws Exception {
        String migration = source("scripts/butler-migrate-runtime-data.ps1");

        assertTrue(migration.contains("Join-Path $repoRoot 'bet\\bet-cli\\butler.db'"));
        assertTrue(migration.contains("Legacy source retained unchanged for rollback."));
        assertFalse(migration.contains("Remove-Item -LiteralPath $sourcePath"));
        assertFalse(migration.contains("Move-Item -LiteralPath $sourcePath"));
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
        throw new IOException("BF-771 test could not locate " + relativePath);
    }
}
