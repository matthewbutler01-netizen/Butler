package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf702WarmCliClassesTest {

    @Test
    void readOnlyCliClassesWarmBeforeAnyPreservedCoreStarts() throws Exception {
        String script = source("scripts/butler-app-shell-core.ps1");

        assertTrue(script.contains("$repoRoot = Split-Path -Parent $scriptDir"));
        assertTrue(script.contains("$gradle = Join-Path $repoRoot 'gradlew.bat'"));
        assertTrue(script.contains("function Initialize-ReadOnlyCliClasses"));
        assertTrue(script.contains("$lines = & $gradle '--no-daemon' ':bet:bet-cli:classes' 2>&1"));

        int warm = script.indexOf("    Initialize-ReadOnlyCliClasses\n\n    for ($index = 0; $index -lt $maxCoreWorkers; $index++) {");
        int firstCoreStart = script.indexOf("$process = Start-PreservedCore -BackendPort $backendPort", warm);
        assertTrue(warm >= 0 && firstCoreStart > warm);
    }

    @Test
    void warmupFailureBlocksStartupWithCapturedGradleOutput() throws Exception {
        String script = source("scripts/butler-app-shell-core.ps1");

        assertTrue(script.contains("$exitCode = $LASTEXITCODE"));
        assertTrue(script.contains("$text = ($lines | ForEach-Object { \"$_\" }) -join \"`n\""));
        assertTrue(script.contains("BF-702 BLOCKED: read-only CLI warm-up failed with Gradle exit code $exitCode."));
    }

    @Test
    void warmupDoesNotReduceRuntimePoolOrExpandWriteBoundary() throws Exception {
        String script = source("scripts/butler-app-shell-core.ps1");

        assertTrue(script.contains("$maxCoreWorkers = 6"));
        assertTrue(script.contains("CreateRunspacePool(1, $maxCoreWorkers)"));
        assertTrue(script.contains("while ($activeRequests.Count -ge $maxCoreWorkers)"));
        assertTrue(script.contains("Stop-OwnedProcessTree -Process $backend.Process"));
        assertFalse(script.contains("/refresh"));
        assertFalse(script.contains("create_transaction"));
        assertFalse(script.contains("submitTransaction"));
    }

    @Test
    void bf702CorePoolScriptRemainsAsciiOnly() throws Exception {
        String script = source("scripts/butler-app-shell-core.ps1");
        byte[] encoded = script.getBytes(StandardCharsets.US_ASCII);
        assertEquals(script, new String(encoded, StandardCharsets.US_ASCII));
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
        throw new IOException("BF-702 test could not locate " + relativePath);
    }
}
