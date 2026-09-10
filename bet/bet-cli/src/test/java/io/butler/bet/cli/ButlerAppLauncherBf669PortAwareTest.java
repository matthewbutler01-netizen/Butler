package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppLauncherBf669PortAwareTest {

    @Test
    void launcherPreflightsRequestedPortAndRecognizesButlerShell() throws Exception {
        String script = script();

        assertTrue(script.contains("function Get-AppPortState"));
        assertTrue(script.contains("http://127.0.0.1:$RequestedPort/health"));
        assertTrue(script.contains("[System.Net.WebExceptionStatus]::ConnectFailure"));
        assertTrue(script.contains("return \"FREE\""));
        assertTrue(script.contains("return \"OCCUPIED_BUTLER\""));
        assertTrue(script.contains("return \"OCCUPIED_OTHER\""));
        assertTrue(script.contains("\"service\"\\s*:\\s*\"butler-app-shell\""));
    }

    @Test
    void duplicateAndForeignPortFailuresAreClearAndNeverKillProcesses() throws Exception {
        String script = script();

        assertTrue(script.contains("Butler is already running on port $Port"));
        assertTrue(script.contains("stop its PowerShell window with Ctrl+C"));
        assertTrue(script.contains("local port $Port is already in use by another process"));
        assertTrue(script.contains("Butler will not stop it automatically"));
        assertTrue(script.contains("-Port <free-port>"));

        assertFalse(script.contains("Stop-Process"));
        assertFalse(script.contains("taskkill"));
        assertFalse(script.contains("Get-NetTCPConnection"));
        assertFalse(script.contains("Invoke-Expression"));
    }

    @Test
    void launcherRemainsAsciiOnly() throws Exception {
        String script = script();
        byte[] encoded = script.getBytes(StandardCharsets.US_ASCII);
        assertEquals(script, new String(encoded, StandardCharsets.US_ASCII));
    }

    private static String script() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve("scripts/butler-app.ps1");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-669 test could not locate scripts/butler-app.ps1");
    }
}
