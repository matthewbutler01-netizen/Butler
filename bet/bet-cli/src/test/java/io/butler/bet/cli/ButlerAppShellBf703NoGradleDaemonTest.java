package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf703NoGradleDaemonTest {

    @Test
    void daemonDisableIsEstablishedBeforeWarmupAndWorkerLaunch() throws Exception {
        String script = source("scripts/butler-app-shell-core.ps1");

        assertTrue(script.contains("$gradleNoDaemonOpt = '-Dorg.gradle.daemon=false'"));
        assertTrue(script.contains("function Enable-ButlerGradleNoDaemon"));
        assertTrue(script.contains("$env:GRADLE_OPTS = $existing.TrimEnd() + ' ' + $gradleNoDaemonOpt"));
        assertTrue(script.contains("$lines = & $gradle '--no-daemon' ':bet:bet-cli:classes' 2>&1"));

        int enable = script.indexOf("    Enable-ButlerGradleNoDaemon");
        int warm = script.indexOf("    Initialize-ReadOnlyCliClasses", enable);
        int firstCore = script.indexOf("$process = Start-PreservedCore -BackendPort $backendPort", warm);
        assertTrue(enable >= 0 && warm > enable && firstCore > warm);
    }

    @Test
    void existingGradleOptsArePreservedAndRestored() throws Exception {
        String script = source("scripts/butler-app-shell-core.ps1");

        assertTrue(script.contains("$originalGradleOpts = $env:GRADLE_OPTS"));
        assertTrue(script.contains("if ([string]::IsNullOrWhiteSpace($existing))"));
        assertTrue(script.contains("IndexOf($gradleNoDaemonOpt, [System.StringComparison]::OrdinalIgnoreCase)"));
        assertTrue(script.contains("function Restore-GradleOpts"));
        assertTrue(script.contains("$env:GRADLE_OPTS = $originalGradleOpts"));
        assertTrue(script.contains("Restore-GradleOpts"));
    }

    @Test
    void lifecycleChangeDoesNotReduceConcurrencyOrExpandWriteBoundary() throws Exception {
        String script = source("scripts/butler-app-shell-core.ps1");

        assertTrue(script.contains("$maxCoreWorkers = 6"));
        assertTrue(script.contains("CreateRunspacePool(1, $maxCoreWorkers)"));
        assertTrue(script.contains("while ($activeRequests.Count -ge $maxCoreWorkers)"));
        assertTrue(script.contains("Stop-OwnedProcessTree -Process $backend.Process"));
        assertFalse(script.contains("& $gradle '--stop'"));
        assertFalse(script.contains("/refresh"));
        assertFalse(script.contains("create_transaction"));
        assertFalse(script.contains("submitTransaction"));
    }

    @Test
    void bf703CorePoolScriptRemainsAsciiOnly() throws Exception {
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
        throw new IOException("BF-703 test could not locate " + relativePath);
    }
}
