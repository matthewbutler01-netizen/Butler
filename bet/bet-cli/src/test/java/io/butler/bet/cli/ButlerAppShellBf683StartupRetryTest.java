package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf683StartupRetryTest {

    @Test
    void cmdRoutesThroughBoundedRetryEntrypoint() throws Exception {
        String cmd = script("scripts/butler-app.cmd");
        String entry = script("scripts/butler-app-entry.ps1");

        assertTrue(cmd.contains("butler-app-entry.ps1"));
        assertFalse(cmd.contains("butler-app-guard.ps1"));
        assertTrue(entry.contains("$maxAttempts = 3"));
        assertTrue(entry.contains("$retryMessage = \"BF-670 BLOCKED: preserved Butler app core exited during startup.\""));
        assertTrue(entry.contains("if ($message -cne $retryMessage -or $attempt -ge $maxAttempts)"));
        assertTrue(entry.contains("Start-Sleep -Milliseconds 250"));
    }

    @Test
    void wrapperForwardsOnlyOriginallyBoundLauncherArguments() throws Exception {
        String entry = script("scripts/butler-app-entry.ps1");

        assertTrue(entry.contains("$PSBoundParameters.ContainsKey($name)"));
        assertTrue(entry.contains("$forward[$name] = $PSBoundParameters[$name]"));
        assertTrue(entry.contains("& $guard @forward"));
        assertTrue(entry.contains("LeagueId"));
        assertTrue(entry.contains("Port"));
        assertTrue(entry.contains("NoBrowser"));
        assertTrue(entry.contains("ResetLeague"));
    }

    @Test
    void retrySliceDoesNotModifyCoreGovernancePaths() throws Exception {
        String entry = script("scripts/butler-app-entry.ps1");

        assertFalse(entry.contains("sleeperLiveWaiver"));
        assertFalse(entry.contains("BF-641"));
        assertFalse(entry.contains("FAAB"));
        assertFalse(entry.contains("Invoke-WebRequest"));
        assertFalse(entry.contains("<script"));
        assertAscii(entry);
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
        throw new IOException("BF-683 test could not locate " + relativePath);
    }
}
