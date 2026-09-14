package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellCoreRecoveryBf757Test {
    @Test
    void exitedIdleCoreIsRecoveredBeforeItCanBeDispatched() throws Exception {
        String source = source("scripts/butler-app-shell-core.ps1");
        String selector = between(source, "function Get-FreeBackendPort {", "try {\n    Enable-ButlerGradleNoDaemon");

        assertTrue(selector.contains("$hasExited = [bool]$backend.Process.HasExited"));
        assertTrue(selector.contains("if (-not $hasExited) { return $candidate }"));
        assertTrue(selector.contains("Restart-PreservedCore -BackendIndex $index"));
        assertTrue(selector.contains("Write-Warning"));
        assertTrue(selector.contains("return $null"));
        assertFalse(selector.contains("Start-Job"));
        assertFalse(selector.contains("BeginInvoke"));
        assertFalse(selector.contains("-Parallel"));
    }

    @Test
    void replacementCoreUsesFreshPortThenHealthAndWarmupBeforeEligibility() throws Exception {
        String source = source("scripts/butler-app-shell-core.ps1");
        String recovery = between(source, "function Restart-PreservedCore {", "function Send-HttpResponse {");

        int freePort = recovery.indexOf("$replacementPort = Get-FreeLoopbackPort");
        int excludeExisting = recovery.indexOf("$backendPorts -contains $replacementPort");
        int start = recovery.indexOf("Start-PreservedCore -BackendPort $replacementPort");
        int wait = recovery.indexOf("Wait-PreservedCore -BackendPort $replacementPort -Process $replacementProcess");
        int warm = recovery.indexOf("Invoke-PreservedCoreWarmup -BackendPort $replacementPort");
        int replacePort = recovery.indexOf("$backendPorts[$BackendIndex] = $replacementPort");
        int replaceProcess = recovery.indexOf("$backendProcesses[$BackendIndex] = [pscustomobject]@{");

        assertTrue(freePort >= 0 && excludeExisting > freePort);
        assertTrue(start > excludeExisting && wait > start && warm > wait);
        assertTrue(replacePort > warm && replaceProcess > replacePort,
            "BF-757 must not expose the replacement until it is healthy and warmed");
        assertTrue(recovery.contains("Stop-OwnedProcessTree -Process $replacementProcess"));
        assertFalse(recovery.contains("/refresh"));
        assertFalse(recovery.contains("POST"));
    }

    @Test
    void schedulerRevalidatesAfterAcceptAndFailsClosedOnlyWhenNoCapacityRemains() throws Exception {
        String source = source("scripts/butler-app-shell-core.ps1");
        String dispatch = between(source, "    $requestPool.Open()", "}\nfinally {");

        String accept = "$client = $listener.AcceptTcpClient()";
        String selector = "$backendPort = Get-FreeBackendPort";
        int acceptIndex = dispatch.indexOf(accept);
        int firstSelection = dispatch.indexOf(selector);
        int secondSelection = dispatch.indexOf(selector, firstSelection + selector.length());

        assertTrue(firstSelection >= 0 && acceptIndex > firstSelection,
            "BF-757 must preserve pre-accept capacity selection");
        assertTrue(secondSelection > acceptIndex,
            "BF-757 must revalidate the backend after accept before dispatch");
        assertTrue(dispatch.contains("while ($null -eq $backendPort)"));
        assertTrue(dispatch.contains("if ($activeRequests.Count -eq 0)"));
        assertTrue(dispatch.contains("BF-757 BLOCKED: no healthy preserved inner-core worker is available."));
        assertTrue(dispatch.contains("BF-757 BLOCKED: no healthy preserved inner-core worker is available after accept."));
        assertTrue(dispatch.contains("Remove-CompletedCoreJobs -WaitForOne"));
        assertEquals(2, occurrences(dispatch, selector));
        assertEquals(1, occurrences(source, accept));
    }

    @Test
    void faultInjectionKillsOnlyOwnedCoreAndRunsUnchangedReadOnlyAcceptance() throws Exception {
        String diagnostic = source("scripts/butler-preserved-core-recovery-diagnostic.ps1");

        assertTrue(diagnostic.contains("Stop-OwnedProcessTree -Process $backendProcesses[0].Process"));
        assertTrue(diagnostic.contains("& $acceptance"));
        assertTrue(diagnostic.contains("Test-Bf757BytesEqual"));
        assertTrue(diagnostic.contains("WriteAllBytes($corePath, $originalCoreBytes)"));
        assertTrue(diagnostic.contains("production recovery contract is missing"));
        assertTrue(diagnostic.contains("/refresh excluded"));
        assertFalse(diagnostic.contains("POST"));
        assertFalse(diagnostic.contains("Start-Job"));
        assertFalse(diagnostic.contains("-Parallel"));
    }

    @Test
    void bf757PowerShellSourcesRemainAsciiOnly() throws Exception {
        for (String relative : new String[] {
            "scripts/butler-app-shell-core.ps1",
            "scripts/butler-preserved-core-recovery-diagnostic.ps1"
        }) {
            byte[] bytes = Files.readAllBytes(locate(relative));
            for (byte value : bytes) {
                assertTrue((value & 0xff) <= 0x7f, "BF-757 PowerShell source must remain ASCII-only: " + relative);
            }
        }
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while (true) {
            int index = source.indexOf(needle, offset);
            if (index < 0) return count;
            count++;
            offset = index + needle.length();
        }
    }

    private static String between(String source, String begin, String end) {
        int start = source.indexOf(begin);
        int finish = source.indexOf(end, start + begin.length());
        if (start < 0 || finish < 0 || finish <= start) {
            throw new IllegalStateException("BF-757 test could not locate source boundaries: " + begin + " -> " + end);
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
        throw new IllegalStateException("BF-757 test could not locate " + relativePath);
    }
}
