package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf668TeamIntelligenceTest {

    @Test
    void teamRouteIsAppNativeAndUsesOnlyExistingGovernedReadOnlySources() throws Exception {
        String script = script();

        assertTrue(script.contains("$path -eq \"/team\""));
        assertTrue(script.contains(":bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit"));
        assertTrue(script.contains("league team-context $LeagueId"));
        assertTrue(script.contains("league roster-strength $LeagueId"));
        assertTrue(script.contains("league positional-pressure $LeagueId"));
        assertTrue(script.contains("league team-posture $LeagueId $($rosterView.Season)"));
        assertTrue(script.contains("league future-capital $LeagueId"));
        assertTrue(script.contains("My team intelligence"));
        assertTrue(script.contains("Exact BF-623-bound live roster context from BF-610"));
        assertTrue(script.contains("ConvertTo-HtmlText"));
        assertTrue(script.contains("governed BF-623/BF-610 roster identity is missing or not live verified"));
        assertTrue(script.contains("$knownDashboardPath = $path -eq \"/\" -or $path -eq \"/index.html\" -or $path -eq \"/waivers\" -or $candidate"));

        assertFalse(script.contains("$knownDashboardPath = $path -eq \"/\" -or $path -eq \"/index.html\" -or $path -eq \"/team\""));
        assertFalse(script.contains("sleeperLiveWaiverSnapshotSync"));
        assertFalse(script.contains("sleeperLiveWaiverMarketAttentionSync"));
        assertFalse(script.contains("sleeperLiveWaiverRecommendationAuditCapture"));
        assertFalse(script.contains("sleeperLiveWaiverFinalRecommendationBundle"));
        assertFalse(script.contains("Invoke-Expression"));
        assertFalse(script.contains("Start-Job"));
    }

    @Test
    void teamPageDoesNotInventACompositeScoreOrLineupRecommendation() throws Exception {
        String script = script();

        assertTrue(script.contains("No new team score or strategy model is created here."));
        assertTrue(script.contains("this page does not turn them into start/sit advice."));
        assertTrue(script.contains("It does not create a new score, recommend a lineup, rerank players"));
        assertFalse(script.contains("teamScore ="));
        assertFalse(script.contains("lineupRecommendation ="));
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
            Path candidate = current.resolve("scripts/butler-app-shell-core.ps1");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-668 test could not locate scripts/butler-app-shell-core.ps1");
    }
}
