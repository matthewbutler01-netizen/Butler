package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf698OneCommandAcceptanceTest {

    @Test
    void acceptanceSelectsItsOwnLoopbackPort() throws Exception {
        String runner = source("scripts/butler-acceptance.ps1");

        assertTrue(runner.contains("[System.Net.Sockets.TcpListener]::new($loopback, $probePort)"));
        assertTrue(runner.contains("$probePort = if ($RequestedPort -gt 0) { $RequestedPort } else { 0 }"));
        assertTrue(runner.contains("requested acceptance port $RequestedPort is already in use"));
        assertFalse(runner.contains("Get-NetTCPConnection"));
    }

    @Test
    void acceptanceUsesExistingReadOnlyLoadHarnessAfterExactHealthIdentity() throws Exception {
        String runner = source("scripts/butler-acceptance.ps1");

        assertTrue(runner.contains("butler-read-load-check.ps1"));
        assertTrue(runner.contains("-NoBrowser"));
        assertTrue(runner.contains("[string]$health.service -cne 'butler-app-shell'"));
        assertTrue(runner.contains("[string]$health.status -cne 'ok'"));
        assertTrue(runner.contains("& $loadCheck -BaseUrl ($root + '/')"));
        assertFalse(runner.contains("/refresh"));
    }

    @Test
    void acceptanceStopsOnlyTheProcessTreeItStarted() throws Exception {
        String runner = source("scripts/butler-acceptance.ps1");

        assertTrue(runner.contains("$process = Start-OwnedButler -SelectedPort $selectedPort"));
        assertTrue(runner.contains("$ownedPid = $process.Id"));
        assertTrue(runner.contains("& $taskkill /PID $Process.Id /T /F"));
        assertTrue(runner.contains("Remove-OwnedRunState -ProcessId $ownedPid -SelectedPort $selectedPort"));
        assertFalse(runner.contains("Stop-Process"));
    }

    @Test
    void commandWrapperAndRunnerRemainWindowsAsciiSafe() throws Exception {
        String runner = source("scripts/butler-acceptance.ps1");
        String wrapper = source("scripts/butler-acceptance.cmd");

        assertTrue(wrapper.contains("butler-acceptance.ps1"));
        assertTrue(wrapper.contains("%*"));
        assertTrue(runner.chars().allMatch(ch -> ch <= 127));
        assertTrue(wrapper.chars().allMatch(ch -> ch <= 127));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-698 acceptance test could not locate " + relativePath);
    }
}
