package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf690CorePoolIsolationTest {

    @Test
    void coreFrontEndUsesExactlySixBoundedPreservedWorkers() throws Exception {
        String core = script("scripts/butler-app-shell-core.ps1");

        assertTrue(core.contains("$maxCoreWorkers = 6"));
        assertTrue(core.contains("butler-app-shell-core-single.ps1"));
        assertTrue(core.contains("butler-app-core-pool-worker.ps1"));
        assertTrue(core.contains("CreateRunspacePool(1, $maxCoreWorkers)"));
        assertTrue(core.contains("for ($index = 0; $index -lt $maxCoreWorkers; $index++)"));
        assertTrue(core.contains("Start-PreservedCore -BackendPort $backendPort"));
        assertTrue(core.contains("Wait-PreservedCore -BackendPort $backendPort -Process $process"));
        assertTrue(core.contains("while ($activeRequests.Count -ge $maxCoreWorkers)"));
        assertTrue(core.contains("$backendPort = Get-FreeBackendPort"));
        assertTrue(core.contains("$powerShell.AddParameter('BackendPort', $backendPort)"));
        assertTrue(core.contains("$handle = $powerShell.BeginInvoke()"));
        assertTrue(core.contains("BackendPort = $backendPort"));
    }

    @Test
    void corePoolPreservesFiniteRequestAndLoopbackBoundaries() throws Exception {
        String core = script("scripts/butler-app-shell-core.ps1");
        String worker = script("scripts/butler-app-core-pool-worker.ps1");

        assertTrue(core.contains("[System.Net.IPAddress]::Parse('127.0.0.1')"));
        assertTrue(core.contains("[System.Net.Sockets.TcpListener]::new($loopback, $Port)"));
        assertTrue(core.contains("$client.ReceiveTimeout = 3000"));
        assertTrue(core.contains("$client.SendTimeout = 10000"));
        assertTrue(worker.contains("$parts[0] -ne 'GET'"));
        assertTrue(worker.contains("-Body 'GET only'"));
        assertTrue(worker.contains("$requestTarget.Length -gt 16384"));
        assertTrue(worker.contains("$path -eq '/refresh'"));
        assertTrue(worker.contains("-StatusCode 404"));
        assertTrue(worker.contains("butler-app-shell-core-pool"));
        assertTrue(worker.contains("http://127.0.0.1:$BackendPort$RequestTarget"));
        assertTrue(worker.contains("$request.Method = 'GET'"));
        assertTrue(worker.contains("$request.Proxy = $null"));
        assertTrue(worker.contains("$request.KeepAlive = $false"));
        assertTrue(worker.contains("$request.AllowAutoRedirect = $false"));
        assertFalse(worker.contains("POST /refresh"));
        assertFalse(worker.contains("create_transaction"));
        assertFalse(worker.contains("submitTransaction"));
        assertFalse(worker.contains("waiver_budget"));
        assertFalse(worker.contains("Invoke-Expression"));
    }

    @Test
    void preservedSingleCoreStillOwnsTheEstablishedReadRoutes() throws Exception {
        String preserved = script("scripts/butler-app-shell-core-single.ps1");

        assertTrue(preserved.contains("$path -eq \"/league\""));
        assertTrue(preserved.contains("$path -eq \"/team\""));
        assertTrue(preserved.contains(":bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit"));
        assertTrue(preserved.contains("Invoke-GovernedDashboardGet -InnerPort $innerPort -Path $path"));
        assertTrue(preserved.contains("Exact BF-623-bound live roster context from BF-610"));
        assertTrue(preserved.contains("READ ONLY."));
    }

    @Test
    void ownedCoreProcessTreesAreCleanedUpOnShutdown() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");
        String core = script("scripts/butler-app-shell-core.ps1");

        assertTrue(shell.contains("function Stop-AppCoreTree"));
        assertTrue(shell.contains("& $taskkill /PID $Process.Id /T /F"));
        assertTrue(shell.contains("Stop-AppCoreTree -Process $coreProcess"));
        assertTrue(shell.contains("$attempt -lt 240"));
        assertTrue(core.contains("function Stop-OwnedProcessTree"));
        assertTrue(core.contains("& $taskkill /PID $Process.Id /T /F"));
        assertTrue(core.contains("Stop-OwnedProcessTree -Process $backend.Process"));
    }

    @Test
    void bf690ScriptsRemainAsciiOnly() throws Exception {
        assertAscii(script("scripts/butler-app-shell.ps1"));
        assertAscii(script("scripts/butler-app-shell-core.ps1"));
        assertAscii(script("scripts/butler-app-shell-core-single.ps1"));
        assertAscii(script("scripts/butler-app-core-pool-worker.ps1"));
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
        throw new IOException("BF-690 test could not locate " + relativePath);
    }
}
