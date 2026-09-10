package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppGuardBf673DefaultPortTest {

    @Test
    void normalLaunchDistinguishesOmittedPortAndUsesManagedLoopbackRange() throws Exception {
        String guard = script("scripts/butler-app-guard.ps1");

        assertTrue(guard.contains("$portWasExplicit = $PSBoundParameters.ContainsKey(\"Port\")"));
        assertTrue(guard.contains("$managedPorts = 8080..8099"));
        assertTrue(guard.contains("[System.Net.IPAddress]::Parse(\"127.0.0.1\")"));
        assertTrue(guard.contains("[System.Net.Sockets.TcpListener]::new($loopback, $CandidatePort)"));
        assertTrue(guard.contains("if (-not $portWasExplicit)"));
        assertTrue(guard.contains("foreach ($candidatePort in $managedPorts)"));
        assertTrue(guard.contains("Butler default port 8080 is unavailable. Using local port $Port instead."));
        assertTrue(guard.contains("no free Butler loopback port is available from 8080 through 8099"));
    }

    @Test
    void existingManagedButlerUsesProvenCreatedNewMutexDetectionBeforeFreePortSelection() throws Exception {
        String guard = script("scripts/butler-app-guard.ps1");

        assertTrue(guard.contains("function Get-ExistingManagedButlerPort"));
        assertTrue(guard.contains("$candidateMutexName = \"Local\\\\Butler.App.Port.$candidatePort\""));
        assertTrue(guard.contains("$candidateCreatedNew = $false"));
        assertTrue(guard.contains("[ref]$candidateCreatedNew"));
        assertTrue(guard.contains("if (-not $candidateCreatedNew)"));
        assertTrue(guard.contains("$candidateMutex.Dispose()"));
        assertFalse(guard.contains("Mutex]::OpenExisting"));
        assertTrue(guard.contains("$existingManagedPort = Get-ExistingManagedButlerPort"));
        assertTrue(guard.contains("$Port = [int]$existingManagedPort"));

        int existing = guard.indexOf("$existingManagedPort = Get-ExistingManagedButlerPort");
        int freeScan = guard.indexOf("$selectedPort = $null");
        assertTrue(existing >= 0 && freeScan > existing);
    }

    @Test
    void explicitPortKeepsExactBf669Behavior() throws Exception {
        String guard = script("scripts/butler-app-guard.ps1");

        assertTrue(guard.contains("Explicit -Port remains the"));
        assertTrue(guard.contains("$mutexName = \"Local\\\\Butler.App.Port.$Port\""));
        assertTrue(guard.contains("[System.Threading.Mutex]::new($false, $mutexName, [ref]$createdNew)"));
        assertTrue(guard.contains("Butler is already running on port $Port"));
        assertTrue(guard.contains("Port = $Port"));
        assertFalse(guard.contains("$Port = 8081"));
        assertFalse(guard.contains("$Port++"));
    }

    @Test
    void defaultPortResolutionNeverInspectsOrKillsOtherProcesses() throws Exception {
        String guard = script("scripts/butler-app-guard.ps1");

        assertFalse(guard.contains("Stop-Process"));
        assertFalse(guard.contains("taskkill"));
        assertFalse(guard.contains("Get-NetTCPConnection"));
        assertFalse(guard.contains("Get-CimInstance"));
        assertFalse(guard.contains("Get-Process"));
        assertFalse(guard.contains("Invoke-Expression"));
        assertTrue(guard.contains("Butler did not stop or modify any listener."));
    }

    @Test
    void bf673GuardRemainsAsciiOnly() throws Exception {
        assertAscii(script("scripts/butler-app-guard.ps1"));
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
        throw new IOException("BF-673 test could not locate " + relativePath);
    }
}
