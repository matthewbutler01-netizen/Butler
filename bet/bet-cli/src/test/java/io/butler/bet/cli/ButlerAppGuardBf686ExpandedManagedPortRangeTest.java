package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppGuardBf686ExpandedManagedPortRangeTest {

    @Test
    void automaticManagedRangeExtendsThrough8199With8080StillFirst() throws Exception {
        String guard = script("scripts/butler-app-guard.ps1");

        assertTrue(guard.contains("[int]$Port = 8080"));
        assertTrue(guard.contains("$managedPorts = 8080..8199"));
        assertFalse(guard.contains("$managedPorts = 8080..8099"));
        assertTrue(guard.contains("no free Butler loopback port is available from 8080 through 8199"));
        assertFalse(guard.contains("no free Butler loopback port is available from 8080 through 8099"));
        assertTrue(guard.contains("Butler default port 8080 is unavailable. Using local port $Port instead."));
    }

    @Test
    void automaticSelectionAndDuplicateDiscoveryUseTheSameExpandedRange() throws Exception {
        String guard = script("scripts/butler-app-guard.ps1");
        String discovery = section(guard, "function Get-ExistingManagedButlerPort", "function Use-ExistingManagedButler");
        String automatic = section(guard, "if (-not $portWasExplicit)", "$portLock = $null");

        assertTrue(discovery.contains("foreach ($candidatePort in $managedPorts)"));
        assertTrue(discovery.contains("Test-ButlerPortLockHeld -CandidatePort $candidatePort"));
        assertTrue(automatic.contains("$existingManagedPort = Get-ExistingManagedButlerPort"));
        assertTrue(automatic.contains("foreach ($candidatePort in $managedPorts)"));
        assertTrue(automatic.contains("Test-LoopbackPortBindable -CandidatePort $candidatePort"));
        assertTrue(automatic.contains("break"));
    }

    @Test
    void explicitPortRemainsExactAndOutsideAutomaticFallback() throws Exception {
        String guard = script("scripts/butler-app-guard.ps1");

        assertTrue(guard.contains("$portWasExplicit = $PSBoundParameters.ContainsKey(\"Port\")"));
        assertTrue(guard.contains("# exact BF-669 contract and never silently moves to a different local port."));
        assertTrue(guard.contains("if (-not $portWasExplicit)"));
        assertTrue(guard.contains("$mutexName = \"Local\\Butler.App.Port.$Port\""));
        assertTrue(guard.contains("BF-669 BLOCKED: Butler is already running on port $Port"));
        assertFalse(guard.contains("$Port++"));
        assertFalse(guard.contains("$Port = 8081"));
    }

    @Test
    void bf686PreservesLoopbackOnlyNoKillBoundary() throws Exception {
        String guard = script("scripts/butler-app-guard.ps1");

        assertTrue(guard.contains("[System.Net.IPAddress]::Parse(\"127.0.0.1\")"));
        assertTrue(guard.contains("[System.Net.Sockets.TcpListener]::new($loopback, $CandidatePort)"));
        assertTrue(guard.contains("Butler did not stop or modify any listener."));
        assertFalse(guard.contains("Stop-Process"));
        assertFalse(guard.contains("taskkill"));
        assertFalse(guard.contains("Get-NetTCPConnection"));
        assertFalse(guard.contains("Get-CimInstance"));
        assertFalse(guard.contains("Get-Process"));
        assertFalse(guard.contains("Invoke-Expression"));
        assertAscii(guard);
    }

    private static String section(String text, String startNeedle, String endNeedle) {
        int start = text.indexOf(startNeedle);
        int end = text.indexOf(endNeedle, start + startNeedle.length());
        assertTrue(start >= 0 && end > start, "BF-686 source section is missing");
        return text.substring(start, end);
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
        throw new IOException("BF-686 test could not locate " + relativePath);
    }
}
