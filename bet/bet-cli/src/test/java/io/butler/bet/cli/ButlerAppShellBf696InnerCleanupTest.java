package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf696InnerCleanupTest {

    @Test
    void innerPoolCleanupDoesNotArrayWrapGenericLists() throws Exception {
        String core = source("scripts/butler-app-shell-core.ps1");
        int cleanupStart = core.indexOf("finally {\n    try { $listener.Stop() } catch {}");
        assertTrue(cleanupStart >= 0);
        String cleanup = core.substring(cleanupStart);

        assertFalse(cleanup.contains("foreach ($job in @($activeRequests))"));
        assertFalse(cleanup.contains("foreach ($backend in @($backendProcesses))"));
        assertTrue(cleanup.contains("for ($index = $activeRequests.Count - 1; $index -ge 0; $index--)"));
        assertTrue(cleanup.contains("$job = $activeRequests[$index]"));
        assertTrue(cleanup.contains("for ($index = $backendProcesses.Count - 1; $index -ge 0; $index--)"));
        assertTrue(cleanup.contains("$backend = $backendProcesses[$index]"));
    }

    @Test
    void innerPoolCapacityAndOwnedCleanupRemainUnchanged() throws Exception {
        String core = source("scripts/butler-app-shell-core.ps1");

        assertTrue(core.contains("$maxCoreWorkers = 6"));
        assertTrue(core.contains("CreateRunspacePool(1, $maxCoreWorkers)"));
        assertTrue(core.contains("Stop-OwnedProcessTree -Process $backend.Process"));
        assertTrue(core.contains("$backendProcesses.Clear()"));
        assertTrue(core.contains("$activeRequests.Clear()"));
        assertTrue(core.contains("try { $requestPool.Close() } catch {}"));
        assertTrue(core.contains("try { $requestPool.Dispose() } catch {}"));
    }

    @Test
    void innerPoolRemainsReadOnlyAndAscii() throws Exception {
        String core = source("scripts/butler-app-shell-core.ps1");

        assertFalse(core.contains("create_transaction"));
        assertFalse(core.contains("submitTransaction"));
        assertFalse(core.contains("waiver_budget"));
        assertFalse(core.contains("/refresh"));

        byte[] encoded = core.getBytes(StandardCharsets.US_ASCII);
        assertEquals(core, new String(encoded, StandardCharsets.US_ASCII));
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
        throw new IOException("BF-696 cleanup test could not locate " + relativePath);
    }
}
