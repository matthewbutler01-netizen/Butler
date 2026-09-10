package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardBf664LauncherTest {

    @Test
    void windowsLauncherUsesProcessLocalExecutionPolicyBypass() throws Exception {
        String launcher = launcher();
        assertTrue(launcher.contains("-NoProfile"));
        assertTrue(launcher.contains("-ExecutionPolicy Bypass"));
        assertTrue(launcher.contains("-File \"%~dp0butler-dashboard.ps1\" %*"));
        assertTrue(launcher.contains("exit /b %ERRORLEVEL%"));
        assertFalse(launcher.contains("Set-ExecutionPolicy"));
    }

    @Test
    void windowsLauncherRemainsAsciiOnly() throws Exception {
        byte[] bytes = Files.readAllBytes(launcherPath());
        for (byte value : bytes) {
            assertTrue((value & 0x80) == 0, "BF-664 launcher must remain ASCII-only");
        }
    }

    private static String launcher() throws IOException {
        return Files.readString(launcherPath(), StandardCharsets.US_ASCII);
    }

    private static Path launcherPath() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve("scripts/butler-dashboard.cmd");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IOException("BF-664 test could not locate scripts/butler-dashboard.cmd");
    }
}
