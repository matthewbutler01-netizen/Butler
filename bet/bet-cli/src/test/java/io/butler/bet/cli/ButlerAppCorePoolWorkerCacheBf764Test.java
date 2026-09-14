package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppCorePoolWorkerCacheBf764Test {
    @Test
    void wrapperCachesParsedImplementationPerPersistentRunspace() throws Exception {
        String wrapper = source("scripts/butler-app-core-pool-worker.ps1");

        assertTrue(wrapper.contains("ButlerBf764CorePoolWorkerScriptBlock"));
        assertTrue(wrapper.contains("Get-Variable -Name $cacheName -Scope Global -ValueOnly"));
        assertTrue(wrapper.contains("[scriptblock]::Create($implementation)"));
        assertTrue(wrapper.contains("Set-Variable -Name $cacheName -Scope Global -Value $worker"));
        assertTrue(wrapper.contains("& $worker -Client $Client -BackendPort $BackendPort"));
        assertTrue(wrapper.contains("butler-app-core-pool-worker-impl.ps1"));
        assertTrue(wrapper.contains("cached core-pool worker has an unexpected type"));
        assertFalse(wrapper.contains("Invoke-PreservedCoreGet"));
        assertFalse(wrapper.contains("Start-Job"));
        assertFalse(wrapper.contains("ForEach-Object -Parallel"));
    }

    @Test
    void canonicalImplementationPreservesOuterReadOnlyProxySafety() throws Exception {
        String worker = source("scripts/butler-app-core-pool-worker-impl.ps1");

        assertTrue(worker.contains("[System.Net.Sockets.TcpClient]$Client"));
        assertTrue(worker.contains("$request.Method = 'GET'"));
        assertTrue(worker.contains("$request.Proxy = $null"));
        assertTrue(worker.contains("$request.KeepAlive = $false"));
        assertTrue(worker.contains("$request.AllowAutoRedirect = $false"));
        assertTrue(worker.contains("if ($parts[0] -ne 'GET')"));
        assertTrue(worker.contains("if ($path -eq '/refresh')"));
        assertTrue(worker.contains("'GET only'"));
        assertTrue(worker.contains("'Not found'"));
        assertTrue(worker.contains("butler-app-shell-core-pool"));
        assertTrue(worker.contains("No Butler or Sleeper write was executed."));
        assertTrue(worker.contains("Content-Security-Policy:"));
        assertTrue(worker.contains("StatusCode 502"));
        assertFalse(worker.contains("Start-Job"));
        assertFalse(worker.contains("ForEach-Object -Parallel"));
    }

    @Test
    void outerSchedulerStillUsesSameSixRunspacePoolAndWorkerEntryPoint() throws Exception {
        String core = source("scripts/butler-app-shell-core.ps1");

        assertTrue(core.contains("$maxCoreWorkers = 6"));
        assertTrue(core.contains("CreateRunspacePool(1, $maxCoreWorkers)"));
        assertTrue(core.contains("$powerShell.AddCommand($requestWorker)"));
        assertTrue(core.contains("$powerShell.AddParameter('Client', $client)"));
        assertTrue(core.contains("$powerShell.AddParameter('BackendPort', $backendPort)"));
        assertTrue(core.contains("Restart-PreservedCore -BackendIndex $index"));
    }

    @Test
    void windowsWorkerSourcesRemainAsciiOnly() throws Exception {
        for (String relativePath : new String[] {
            "scripts/butler-app-core-pool-worker.ps1",
            "scripts/butler-app-core-pool-worker-impl.ps1"
        }) {
            byte[] bytes = Files.readAllBytes(locate(relativePath));
            for (byte value : bytes) {
                assertTrue((value & 0xff) <= 0x7f, "BF-764 Windows worker source must remain ASCII-only: " + relativePath);
            }
        }
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
        throw new IllegalStateException("BF-764 test could not locate " + relativePath);
    }
}
