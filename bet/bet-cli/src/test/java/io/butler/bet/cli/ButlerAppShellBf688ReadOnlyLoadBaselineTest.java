package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf688ReadOnlyLoadBaselineTest {

    @Test
    void harnessTargetsOnlyLoopbackButlerAppShell() throws Exception {
        String script = script("scripts/butler-read-load-check.ps1");

        assertTrue(script.contains("$uri.Scheme -cne 'http'"));
        assertTrue(script.contains("$uri.Host -ne '127.0.0.1' -and $uri.Host -ne 'localhost'"));
        assertTrue(script.contains("BaseUrl must identify the Butler app root."));
        assertTrue(script.contains("[string]$health.service -cne 'butler-app-shell'"));
        assertTrue(script.contains("[string]$health.status -cne 'ok'"));
    }

    @Test
    void pressureSetIsFixedReadOnlyAndExcludesRefresh() throws Exception {
        String script = script("scripts/butler-read-load-check.ps1");

        assertTrue(script.contains("$paths = @('/health', '/', '/team', '/waivers', '/league', '/trade', '/history')"));
        assertTrue(script.contains("if ($paths -contains '/refresh')"));
        assertTrue(script.contains("/refresh is forbidden in the read-only load path set."));
        assertTrue(script.contains("$request.Method = 'GET'"));
        assertFalse(script.contains("$request.Method = 'POST'"));
        assertFalse(script.contains("Invoke-DecisionRefreshRunner"));
        assertFalse(script.contains("sleeperLiveWaiver"));
    }

    @Test
    void baselineIsBoundedAndReportsLatencyDistribution() throws Exception {
        String script = script("scripts/butler-read-load-check.ps1");

        assertTrue(script.contains("[ValidateRange(1, 32)]"));
        assertTrue(script.contains("[int]$Concurrency = 6"));
        assertTrue(script.contains("[ValidateRange(1, 20)]"));
        assertTrue(script.contains("[int]$RequestsPerPath = 3"));
        assertTrue(script.contains("[ValidateRange(1, 300)]"));
        assertTrue(script.contains("[int]$TimeoutSeconds = 60"));
        assertTrue(script.contains("CreateRunspacePool(1, $Concurrency)"));
        assertTrue(script.contains("Get-PercentileMilliseconds -Values $elapsed -Percentile 0.50"));
        assertTrue(script.contains("Get-PercentileMilliseconds -Values $elapsed -Percentile 0.95"));
        assertTrue(script.contains("'P50Ms', 'P95Ms', 'MaxMs'"));
        assertTrue(script.contains("Overall failures: $totalFailures"));
    }

    @Test
    void harnessVerifiesHealthBeforeAndAfterPressure() throws Exception {
        String script = script("scripts/butler-read-load-check.ps1");

        assertTrue(script.contains("Preflight health: BUTLER_APP_SHELL_VERIFIED"));
        assertTrue(script.contains("Post-run health: BUTLER_APP_SHELL_VERIFIED"));
        assertTrue(script.contains("BF-688 FAILED: $totalFailures read-only requests failed. No Butler or Sleeper write was attempted."));
        assertTrue(script.contains("BF-688 RESULT: COMPLETE"));
    }

    @Test
    void bf688HarnessRemainsAsciiOnly() throws Exception {
        assertAscii(script("scripts/butler-read-load-check.ps1"));
    }

    private static void assertAscii(String text) {
        byte[] encoded = text.getBytes(StandardCharsets.US_ASCII);
        assertEquals(text, new String(encoded, StandardCharsets.US_ASCII));
    }

    private static String script(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-688 test could not locate " + relativePath);
    }
}
