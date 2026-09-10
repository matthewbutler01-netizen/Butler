package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppGuardBf674ReuseExistingTest {

    @Test
    void plainDuplicateReusesExistingManagedInstanceBeforeNewLockAcquisition() throws Exception {
        String guard = script("scripts/butler-app-guard.ps1");

        assertTrue(guard.contains("function Use-ExistingManagedButler"));
        assertTrue(guard.contains("Butler is already running on port $ExistingPort."));
        assertTrue(guard.contains("Local URL: $existingUrl"));
        assertTrue(guard.contains("Use-ExistingManagedButler -ExistingPort $Port"));
        assertTrue(guard.contains("Do not reacquire its lock or mutex"));

        int existing = guard.indexOf("$existingManagedPort = Get-ExistingManagedButlerPort");
        int reuse = guard.indexOf("Use-ExistingManagedButler -ExistingPort $Port");
        int exit = guard.indexOf("exit 0", reuse);
        int acquire = guard.indexOf("$portLock = Open-ButlerPortLock -SelectedPort $Port");
        assertTrue(existing >= 0 && reuse > existing && exit > reuse && acquire > exit);
    }

    @Test
    void plainDuplicateOpensExistingUrlUnlessNoBrowserWasRequested() throws Exception {
        String guard = script("scripts/butler-app-guard.ps1");

        assertTrue(guard.contains("$existingUrl = \"http://127.0.0.1:$ExistingPort/\""));
        assertTrue(guard.contains("if (-not $NoBrowser)"));
        assertTrue(guard.contains("Start-Process -FilePath $existingUrl"));

        int condition = guard.indexOf("if (-not $NoBrowser)");
        int browser = guard.indexOf("Start-Process -FilePath $existingUrl");
        assertTrue(condition >= 0 && browser > condition);
    }

    @Test
    void explicitPortStillKeepsBf669ExactFailClosedGuards() throws Exception {
        String guard = script("scripts/butler-app-guard.ps1");

        assertTrue(guard.contains("Explicit -Port remains the"));
        assertTrue(guard.contains("$mutexName = \"Local\\Butler.App.Port.$Port\""));
        assertTrue(guard.contains("$portLock = Open-ButlerPortLock -SelectedPort $Port"));
        assertTrue(guard.contains("[System.Threading.Mutex]::new($false, $mutexName, [ref]$createdNew)"));
        assertTrue(guard.contains("BF-669 BLOCKED: Butler is already running on port $SelectedPort"));
        assertTrue(guard.contains("BF-669 BLOCKED: Butler is already running on port $Port"));
    }

    @Test
    void reusePathNeverInspectsKillsOrMutatesOtherProcesses() throws Exception {
        String guard = script("scripts/butler-app-guard.ps1");

        assertFalse(guard.contains("Stop-Process"));
        assertFalse(guard.contains("taskkill"));
        assertFalse(guard.contains("Get-NetTCPConnection"));
        assertFalse(guard.contains("Get-CimInstance"));
        assertFalse(guard.contains("Get-Process"));
        assertFalse(guard.contains("Invoke-Expression"));
    }

    @Test
    void bf674GuardRemainsAsciiOnly() throws Exception {
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
        throw new IOException("BF-674 test could not locate " + relativePath);
    }
}
