package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppGuardBf669MutexTest {

    @Test
    void commandRoutesThroughNamedMutexGuard() throws Exception {
        String command = script("scripts/butler-app.cmd");
        String guard = script("scripts/butler-app-guard.ps1");

        assertTrue(command.contains("butler-app-guard.ps1"));
        assertFalse(command.contains("-File \"%~dp0butler-app.ps1\""));

        assertTrue(guard.contains("Local\\Butler.App.Port.$Port"));
        assertTrue(guard.contains("[System.Threading.Mutex]::new($false, $mutexName, [ref]$createdNew)"));
        assertTrue(guard.contains("if (-not $createdNew)"));
        assertTrue(guard.contains("Butler is already running on port $Port"));
        assertTrue(guard.contains("$instanceMutex.Dispose()"));
    }

    @Test
    void guardPreservesLauncherArgumentsAndResetBehavior() throws Exception {
        String guard = script("scripts/butler-app-guard.ps1");

        assertTrue(guard.contains("[string]$LeagueId"));
        assertTrue(guard.contains("[int]$Port = 8080"));
        assertTrue(guard.contains("[switch]$NoBrowser"));
        assertTrue(guard.contains("[switch]$ResetLeague"));
        assertTrue(guard.contains("$arguments.LeagueId = $LeagueId"));
        assertTrue(guard.contains("$arguments.NoBrowser = $true"));
        assertTrue(guard.contains("$arguments.ResetLeague = $true"));
        assertTrue(guard.contains("if ($ResetLeague)"));
        assertTrue(guard.contains("& $appLauncher @arguments"));
    }

    @Test
    void guardDoesNotMutateOrKillOtherProcesses() throws Exception {
        String guard = script("scripts/butler-app-guard.ps1");

        assertFalse(guard.contains("Stop-Process"));
        assertFalse(guard.contains("taskkill"));
        assertFalse(guard.contains("Get-NetTCPConnection"));
        assertFalse(guard.contains("Get-CimInstance"));
        assertFalse(guard.contains("Invoke-Expression"));
    }

    @Test
    void guardAndCommandRemainAsciiOnly() throws Exception {
        assertAscii(script("scripts/butler-app-guard.ps1"));
        assertAscii(script("scripts/butler-app.cmd"));
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
        throw new IOException("BF-669 mutex test could not locate " + relativePath);
    }
}
