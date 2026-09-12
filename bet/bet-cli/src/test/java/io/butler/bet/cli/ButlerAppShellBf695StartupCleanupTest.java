package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf695StartupCleanupTest {

    @Test
    void startupCleanupDoesNotArrayWrapGenericActiveRequestList() throws Exception {
        String cleanup = cleanupBlock();

        assertFalse(cleanup.contains("foreach ($job in @($activeRequests))"));
        assertTrue(cleanup.contains("for ($index = $activeRequests.Count - 1; $index -ge 0; $index--)"));
        assertTrue(cleanup.contains("$job = $activeRequests[$index]"));
        assertTrue(cleanup.contains("$activeRequests.Clear()"));
        assertTrue(cleanup.contains("Stop-AppCoreTree -Process $coreProcess"));
    }

    @Test
    void requestPoolAndCoreCleanupRemainBestEffort() throws Exception {
        String cleanup = cleanupBlock();

        assertTrue(cleanup.contains("try { $listener.Stop() } catch {}"));
        assertTrue(cleanup.contains("try { $job.PowerShell.Dispose() } catch {}"));
        assertTrue(cleanup.contains("try { $requestPool.Close() } catch {}"));
        assertTrue(cleanup.contains("try { $requestPool.Dispose() } catch {}"));
    }

    private static String cleanupBlock() throws IOException {
        String shell = source("scripts/butler-app-shell.ps1");
        String marker = "finally {\n    try { $listener.Stop() } catch {}";
        int finallyStart = shell.indexOf(marker);
        assertTrue(finallyStart >= 0);
        return shell.substring(finallyStart);
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
        throw new IOException("BF-695 cleanup test could not locate " + relativePath);
    }
}
