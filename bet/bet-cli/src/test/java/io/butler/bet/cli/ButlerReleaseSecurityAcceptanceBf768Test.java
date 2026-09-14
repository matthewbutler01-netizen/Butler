package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerReleaseSecurityAcceptanceBf768Test {

    @Test
    void runtimeSmokePinsLoopbackHeadersMethodAndRefreshBoundaries() throws Exception {
        String check = source("scripts/butler-release-security-check.ps1");

        assertTrue(check.contains("$baseUri.Host -cne '127.0.0.1'"));
        assertTrue(check.contains("$baseUri.Scheme -cne 'http'"));
        assertTrue(check.contains("default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"));
        assertTrue(check.contains("Cache-Control"));
        assertTrue(check.contains("X-Content-Type-Options"));
        assertTrue(check.contains("Content-Security-Policy"));

        assertTrue(check.contains("Invoke-Bf768Request -Method 'GET' -Path '/health'"));
        assertTrue(check.contains("Invoke-Bf768Request -Method 'POST' -Path '/health'"));
        assertTrue(check.contains("Expected 405") || check.contains("-Expected 405"));
        assertTrue(check.contains("Invoke-Bf768Request -Method 'GET' -Path '/refresh?bf768=1'"));
        assertTrue(check.contains("-Expected 400"));
        assertTrue(check.contains("Invoke-Bf768Request -Method 'GET' -Path '/refresh'"));
        assertTrue(check.contains("[0-9a-f]{64}"));
        assertTrue(check.contains("<form method=\"post\" action=\"/refresh\">"));
        assertTrue(check.contains("(?i)<script\\b"));
        assertTrue(check.contains("(?i)javascript\\s*:"));
        assertTrue(check.contains("BF-768 RELEASE SECURITY: PASS"));

        assertFalse(check.contains("Invoke-Bf768Request -Method 'POST' -Path '/refresh'"));
        assertFalse(check.contains("sleeperLiveWaiverSnapshotSync"));
        assertFalse(check.contains("sleeperLiveWaiverRecommendationAuditCapture"));
        assertFalse(check.contains("create_transaction"));
        assertFalse(check.contains("submitTransaction"));
        assertFalse(check.contains("waiver_budget"));
    }

    @Test
    void ownedRunnerUsesFiniteHealthWaitAndExactProcessTreeCleanup() throws Exception {
        String runner = source("scripts/butler-release-security-acceptance.ps1");

        assertTrue(runner.contains("butler-app.ps1"));
        assertTrue(runner.contains("butler-release-security-check.ps1"));
        assertTrue(runner.contains("[DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)"));
        assertTrue(runner.contains("[string]$health.service -cne 'butler-app-shell'"));
        assertTrue(runner.contains("[string]$health.bind -cne '127.0.0.1'"));
        assertTrue(runner.contains("& $taskkill /PID $Process.Id /T /F"));
        assertTrue(runner.contains("& $securityCheck -BaseUrl ($root + '/')"));
        assertTrue(runner.contains("BF-768 RELEASE SECURITY ACCEPTANCE: PASS"));

        assertFalse(runner.contains("Get-Process java"));
        assertFalse(runner.contains("Stop-Process -Name"));
        assertFalse(runner.contains("POST /refresh may"));
        assertFalse(runner.contains("sleeperLiveWaiverSnapshotSync"));
    }

    @Test
    void oneCommandAcceptanceRunsSecurityAfterPerformanceAndBeforeDiagnosticOptOut() throws Exception {
        String cmd = source("scripts/butler-acceptance.cmd");

        int performance = cmd.indexOf("butler-acceptance.ps1");
        int security = cmd.indexOf("butler-release-security-acceptance.ps1");
        int optOut = cmd.indexOf("set \"BUTLER_APP_PERSISTENT_CORE_WORKER=0\"");
        int slowRoute = cmd.indexOf("butler-slow-route-stage-diagnostic.ps1");

        assertTrue(performance >= 0);
        assertTrue(security > performance, "BF-768 must run after BF-698/BF-688 performance acceptance");
        assertTrue(optOut > security, "BF-768 must use production persistent-worker defaults before diagnostic opt-out");
        assertTrue(slowRoute > optOut, "existing diagnostic sequence must remain after BF-768");
        assertTrue(cmd.contains("if errorlevel 1 exit /b %ERRORLEVEL%"));
    }

    @Test
    void bf768WindowsSourcesRemainAsciiOnly() throws Exception {
        assertAscii(source("scripts/butler-release-security-check.ps1"));
        assertAscii(source("scripts/butler-release-security-acceptance.ps1"));
        assertAscii(source("scripts/butler-acceptance.cmd"));
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
        throw new IOException("BF-768 test could not locate " + relativePath);
    }
}
