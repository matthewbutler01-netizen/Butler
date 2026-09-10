package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppLauncherBf666ScriptTest {

    @Test
    void powershellLauncherUsesExplicitLocalSelectionAndDelegatesOnlyToAppShell() throws Exception {
        String script = script("scripts/butler-app.ps1");

        assertTrue(script.contains("$localAppData = $env:LOCALAPPDATA"));
        assertTrue(script.contains("$configPath = Join-Path $configDir \"app-league.txt\""));
        assertTrue(script.contains("[Guid]::TryParse($candidate, [ref]$parsed)"));
        assertTrue(script.contains("-ResetLeague"));
        assertTrue(script.contains("already configured for a different league"));
        assertTrue(script.contains("$appShell = Join-Path $scriptDir \"butler-app-shell.ps1\""));
        assertTrue(script.contains("& $appShell -LeagueId $selectedLeagueId -Port $Port -NoBrowser"));
        assertTrue(script.contains("& $appShell -LeagueId $selectedLeagueId -Port $Port"));

        assertFalse(script.contains("gradlew"));
        assertFalse(script.contains("SleeperClient"));
        assertFalse(script.contains("sleeperLiveWaiverSnapshotSync"));
        assertFalse(script.contains("sleeperLiveWaiverMarketAttentionSync"));
        assertFalse(script.contains("sleeperLiveWaiverRecommendationAuditCapture"));
        assertFalse(script.contains("sleeperLiveWaiverFinalRecommendationBundle"));
    }

    @Test
    void commandLauncherUsesWindowsPowerShellAndForwardsAllArguments() throws Exception {
        String command = script("scripts/butler-app.cmd");

        assertTrue(command.contains("WindowsPowerShell\\v1.0\\powershell.exe"));
        assertTrue(command.contains("-File \"%~dp0butler-app.ps1\" %*"));
        assertTrue(command.contains("exit /b %ERRORLEVEL%"));
    }

    @Test
    void launcherScriptsRemainAsciiOnly() throws Exception {
        assertAscii(script("scripts/butler-app.ps1"));
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
        throw new IOException("BF-666 test could not locate " + relativePath);
    }
}
