package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf700SequentialCoreStartupTest {

    @Test
    void eachPreservedCoreBecomesHealthyBeforeTheNextStarts() throws Exception {
        String script = source("scripts/butler-app-shell-core.ps1");

        assertTrue(script.contains("$maxCoreWorkers = 6"));

        int loopStart = script.indexOf("for ($index = 0; $index -lt $maxCoreWorkers; $index++) {");
        int poolOpen = script.indexOf("$requestPool.Open()", loopStart);
        assertTrue(loopStart >= 0 && poolOpen > loopStart);
        String startup = script.substring(loopStart, poolOpen);

        int start = startup.indexOf("$process = Start-PreservedCore -BackendPort $backendPort");
        int add = startup.indexOf("$backendProcesses.Add", start);
        int wait = startup.indexOf("Wait-PreservedCore -BackendPort $backendPort -Process $process", add);
        assertTrue(start >= 0 && add > start && wait > add);

        assertFalse(script.contains("foreach ($backend in $backendProcesses) {\n        Wait-PreservedCore"));
    }

    @Test
    void sequentialStartupDoesNotReduceRuntimePoolOrOwnedCleanup() throws Exception {
        String script = source("scripts/butler-app-shell-core.ps1");

        assertTrue(script.contains("CreateRunspacePool(1, $maxCoreWorkers)"));
        assertTrue(script.contains("while ($activeRequests.Count -ge $maxCoreWorkers)"));
        assertTrue(script.contains("Get-FreeBackendPort"));
        assertTrue(script.contains("Stop-OwnedProcessTree -Process $backend.Process"));
        assertTrue(script.contains("for ($index = $backendProcesses.Count - 1; $index -ge 0; $index--)"));
    }

    @Test
    void corePoolScriptRemainsAsciiOnly() throws Exception {
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
        throw new IOException("BF-700 test could not locate " + relativePath);
    }
}
