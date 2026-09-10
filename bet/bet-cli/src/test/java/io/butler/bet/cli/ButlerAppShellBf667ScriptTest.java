package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf667ScriptTest {

    @Test
    void shellKeepsExistingDashboardBehindLoopbackAndOwnsLeagueRoute() throws Exception {
        String script = script();

        assertTrue(script.contains("$loopback = [System.Net.IPAddress]::Parse(\"127.0.0.1\")"));
        assertTrue(script.contains("$dashboard = Join-Path $scriptDir \"butler-dashboard.ps1\""));
        assertTrue(script.contains("-Port $InnerPort -NoBrowser"));
        assertTrue(script.contains("Get-FreeLoopbackPort"));
        assertTrue(script.contains("Wait-ForGovernedDashboard"));
        assertTrue(script.contains("$path -eq \"/league\""));
        assertTrue(script.contains("href=\"/league\""));
        assertTrue(script.contains("Add-LeagueNavigation"));
        assertTrue(script.contains("$parts[0] -ne \"GET\""));
        assertTrue(script.contains("$dashboardProcess.Kill()"));
    }

    @Test
    void leaguePageUsesExistingGovernedOverviewWithoutAddingFootballLogic() throws Exception {
        String script = script();

        assertTrue(script.contains("& $gradle \":bet:bet-cli:run\" \"--args=league overview $LeagueId\""));
        assertTrue(script.contains("Franchise rankings are unavailable until Butler reports current asset coverage as READY"));
        assertTrue(script.contains("Value movement is unavailable until comparable provider snapshots exist"));
        assertTrue(script.contains("These are Butler's existing deterministic league-health actions"));
        assertTrue(script.contains("ConvertTo-HtmlText"));
        assertTrue(script.contains("READ ONLY."));

        assertFalse(script.contains("sleeperLiveWaiverSnapshotSync"));
        assertFalse(script.contains("sleeperLiveWaiverMarketAttentionSync"));
        assertFalse(script.contains("sleeperLiveWaiverRecommendationAuditCapture"));
        assertFalse(script.contains("sleeperLiveWaiverFinalRecommendationBundle"));
        assertFalse(script.contains("Invoke-Expression"));
        assertFalse(script.contains("Start-Job"));
    }

    @Test
    void appShellRemainsAsciiOnly() throws Exception {
        String script = script();
        byte[] encoded = script.getBytes(StandardCharsets.US_ASCII);
        assertEquals(script, new String(encoded, StandardCharsets.US_ASCII));
    }

    private static String script() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve("scripts/butler-app-shell.ps1");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-667 test could not locate scripts/butler-app-shell.ps1");
    }
}
