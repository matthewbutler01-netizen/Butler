package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf687StartupPresentationTest {

    @Test
    void successfulStartupUsesProductLanguageInsteadOfInternalBfLabels() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");

        assertTrue(shell.contains("Write-Host 'Butler App Shell'"));
        assertTrue(shell.contains("Write-Host \"App core: isolated on internal loopback port $innerPort\""));
        assertTrue(shell.contains("governed Butler refresh cycle; no Sleeper transaction write exists."));

        assertFalse(shell.contains("Write-Host 'Butler App Shell (BF-675)'"));
        assertFalse(shell.contains("Preserved BF-668 app core: isolated on internal loopback port"));
        assertFalse(shell.contains("BF-675 Butler refresh cycle"));
    }

    @Test
    void startupStillShowsEndpointsLoopbackAndSafetyBoundary() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");

        assertTrue(shell.contains("Local URL: $url"));
        assertTrue(shell.contains("My Team: http://127.0.0.1:$Port/team"));
        assertTrue(shell.contains("Waiver Board: http://127.0.0.1:$Port/waivers"));
        assertTrue(shell.contains("League: http://127.0.0.1:$Port/league"));
        assertTrue(shell.contains("Trade Lab: http://127.0.0.1:$Port/trade"));
        assertTrue(shell.contains("History: http://127.0.0.1:$Port/history"));
        assertTrue(shell.contains("Manual decision refresh: http://127.0.0.1:$Port/refresh"));
        assertTrue(shell.contains("Bind: 127.0.0.1 only"));
        assertTrue(shell.contains("all existing app pages remain GET/read-only"));
        assertTrue(shell.contains("Only exact token-gated POST /refresh"));
        assertTrue(shell.contains("no Sleeper transaction write exists."));
    }

    @Test
    void bfCodedFailureDiagnosticsRemainIntact() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");

        assertTrue(shell.contains("BF-670 BLOCKED: required Butler app component not found at $required"));
        assertTrue(shell.contains("BF-670 BLOCKED: Windows PowerShell 5.1 executable not found."));
        assertTrue(shell.contains("BF-675 BLOCKED: refresh one-use token is missing, expired, replayed, or invalid."));
        assertTrue(shell.contains("BF-675 refresh confirmation accepts no query parameters."));
    }

    @Test
    void presentationChangeDoesNotAlterRefreshExecutionContract() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");

        assertTrue(shell.contains("$decisionRefreshToken = New-DecisionRefreshToken"));
        assertTrue(shell.contains("Invoke-DecisionRefreshRunner -LeagueId $LeagueId -RunnerPath $decisionRefreshRunner"));
        assertTrue(shell.contains("if ($requestTarget -cne '/refresh')"));
        assertTrue(shell.contains("if ($parts[0] -ne 'GET')"));
    }

    @Test
    void bf687ShellRemainsAsciiOnly() throws Exception {
        assertAscii(script("scripts/butler-app-shell.ps1"));
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
        throw new IOException("BF-687 test could not locate " + relativePath);
    }
}
