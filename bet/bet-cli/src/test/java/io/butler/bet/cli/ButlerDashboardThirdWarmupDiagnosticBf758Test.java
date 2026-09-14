package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardThirdWarmupDiagnosticBf758Test {
    @Test
    void productionRemainsWaiverThenTeamWithoutDashboardWarmup() throws Exception {
        String source = source("scripts/butler-app-shell-core.ps1");
        String warmup = between(source, "function Invoke-PreservedCoreWarmup {", "function Stop-OwnedProcessTree {");

        String waiverUrl = "http://127.0.0.1:$BackendPort/waivers";
        String teamUrl = "http://127.0.0.1:$BackendPort/team";
        String dashboardCreate = "Create(\"http://127.0.0.1:$BackendPort/\")";
        assertEquals(1, occurrences(warmup, waiverUrl));
        assertEquals(1, occurrences(warmup, teamUrl));
        assertTrue(warmup.indexOf(waiverUrl) < warmup.indexOf(teamUrl));
        assertFalse(warmup.contains(dashboardCreate));
        assertFalse(warmup.contains("/refresh"));
    }

    @Test
    void diagnosticAddsOneBoundedDashboardGetAfterBothProductionWarmupCallSites() throws Exception {
        String source = source("scripts/butler-dashboard-third-warmup-diagnostic.ps1");

        assertTrue(source.contains("BF-758 temporary preserved-core warmup profile: /waivers then /team then /"));
        assertTrue(source.contains("http://127.0.0.1:$BackendPort/"));
        assertTrue(source.contains("$request.Method = 'GET'"));
        assertTrue(source.contains("$request.Timeout = 3000"));
        assertTrue(source.contains("$request.ReadWriteTimeout = 3000"));
        assertTrue(source.contains("$request.Proxy = $null"));
        assertTrue(source.contains("$request.KeepAlive = $false"));
        assertTrue(source.contains("expected exactly two production warmup call sites (startup and BF-757 recovery)"));
        assertTrue(source.contains("Invoke-Bf758PreservedCoreDashboardWarmup -BackendPort `$backendPort"));
        assertTrue(source.contains("including BF-757 replacement-core recovery"));
        assertFalse(source.contains("Start-Job"));
        assertFalse(source.contains("-Parallel"));
        assertFalse(source.contains("worktree"));
    }

    @Test
    void diagnosticRefusesDirtyCoreAndRestoresExactBytesAndEnvironment() throws Exception {
        String source = source("scripts/butler-dashboard-third-warmup-diagnostic.ps1");

        assertTrue(source.contains("@('diff', '--quiet', '--', 'scripts/butler-app-shell-core.ps1')"));
        assertTrue(source.contains("already has local changes; refusing temporary diagnostic patch"));
        assertTrue(source.contains("[System.IO.File]::ReadAllBytes($corePath)"));
        assertTrue(source.contains("[System.IO.File]::WriteAllBytes($corePath, $originalCoreBytes)"));
        assertTrue(source.contains("Test-Bf758BytesEqual"));
        assertTrue(source.contains("BF-758 CLEANUP FAILED"));
        assertTrue(source.contains("Restore-Bf758EnvironmentValue -Name 'BUTLER_APP_PERSISTENT_CORE_WORKER'"));
        assertTrue(source.contains("Restore-Bf758EnvironmentValue -Name 'BUTLER_APP_SLEEPER_TRANSPORT_PREWARM'"));
        assertTrue(source.contains("Restore-Bf758EnvironmentValue -Name 'BUTLER_APP_CORE_POOL_WARMUP'"));
    }

    @Test
    void diagnosticPreservesReadOnlyBoundaryAndAsciiSource() throws Exception {
        String source = source("scripts/butler-dashboard-third-warmup-diagnostic.ps1");
        assertTrue(source.contains("/refresh excluded"));
        assertTrue(source.contains("no Butler or Sleeper write path is invoked"));
        assertFalse(source.contains("POST"));

        byte[] bytes = Files.readAllBytes(locate("scripts/butler-dashboard-third-warmup-diagnostic.ps1"));
        for (byte value : bytes) {
            assertTrue((value & 0xff) <= 0x7f, "BF-758 PowerShell source must remain ASCII-only");
        }
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private static String between(String source, String begin, String end) {
        int start = source.indexOf(begin);
        int finish = source.indexOf(end, start + begin.length());
        if (start < 0 || finish < 0 || finish <= start) {
            throw new IllegalStateException("BF-758 test could not locate source boundaries");
        }
        return source.substring(start, finish);
    }

    private static String source(String relativePath) throws Exception {
        return Files.readString(locate(relativePath), StandardCharsets.UTF_8);
    }

    private static Path locate(String relativePath) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException("BF-758 test could not locate " + relativePath);
    }
}
