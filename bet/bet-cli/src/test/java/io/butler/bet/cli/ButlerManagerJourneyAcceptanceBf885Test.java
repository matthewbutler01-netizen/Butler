package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerManagerJourneyAcceptanceBf885Test {

    @Test
    void journeyCoversPolishedManagerSurfaces() throws Exception {
        String script = source("scripts/butler-manager-journey-acceptance.ps1");

        for (String marker : new String[]{
                "Butler end-to-end manager journey acceptance (BF-885)",
                "Dashboard",
                "My Team",
                "Matchup",
                "Player Search",
                "Player Detail",
                "League",
                "Franchise Detail",
                "Waiver Board",
                "Trade Analyzer",
                "Decision History",
                "Manager not-found recovery",
                "Health before journey",
                "Health after journey",
                "Working tree: CLEAN",
                "BF-885 RESULT: COMPLETE"
        }) {
            assertTrue(script.contains(marker), "BF-885 journey missing " + marker);
        }
    }

    @Test
    void detailIdentityComesFromRenderedInternalLinks() throws Exception {
        String script = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(script.contains("function Get-FirstSafeHref"));
        assertTrue(script.contains("href=\"(?<href>/player\\?id=[^\"]+)\""));
        assertTrue(script.contains("href=\"(?<href>/franchise\\?id=[^\"]+)\""));
        assertTrue(script.contains("discovered detail link is not an internal Butler path"));
        assertTrue(script.contains("discovered detail link escaped the Butler origin"));
        assertTrue(script.contains("Write-Skip -Label 'Player Detail'"));
        assertTrue(script.contains("Write-Skip -Label 'Franchise Detail'"));
    }

    @Test
    void journeyIsStrictlyGetOnlyAndExcludesExecutionRoutes() throws Exception {
        String script = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(script.contains("$request.Method = 'GET'"));
        assertTrue(script.contains("no /refresh, no POST, no lineup/waiver/trade execution, no Sleeper write"));

        for (String forbidden : new String[]{
                "Method = 'POST'",
                "Method = \"POST\"",
                "/refresh'",
                "/refresh\"",
                "submitTransaction",
                "setFaab",
                "sleeperCurrentWeekMatchupSync",
                "sleeperLiveWaiverRecommendationAuditCapture",
                "Invoke-RestMethod",
                "Invoke-WebRequest"
        }) {
            assertFalse(script.contains(forbidden), "BF-885 acceptance introduced forbidden action " + forbidden);
        }
    }

    @Test
    void unexpectedManagerStatusSurfacesRecoveryTechnicalDetailBeforeCss() throws Exception {
        String script = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(script.contains("function Get-ManagerRecoveryTechnicalDetail"));
        assertTrue(script.contains("<summary>Technical details</summary><div class=\"technical\">"));
        assertTrue(script.contains("technical=$detail"));
        assertTrue(script.contains("if ($detail.Length -gt 1200)"));
    }

    @Test
    void failedHtmlFallbackStripsStyleAndKeepsRecoveryTail() throws Exception {
        String script = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(script.contains("(?is)<style\\b[^>]*>.*?</style>|<script\\b[^>]*>.*?</script>"));
        assertTrue(script.contains("if ($plain.Length -gt 1600)"));
        assertTrue(script.contains("$plain.Substring($plain.Length - 1600)"));
        assertFalse(script.contains("$plain.Substring(0, 700)"));
    }

    @Test
    void recoveryAndHealthArePartOfTheSameJourney() throws Exception {
        String script = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(script.contains("/__bf885_not_found__"));
        assertTrue(script.contains("Assert-Status -Response $notFound -Expected 404"));
        assertTrue(script.contains("Page not found"));
        assertTrue(script.contains("SAFE RECOVERY."));
        assertTrue(script.contains("$root + '/health'"));
        assertTrue(script.contains("service -cne 'butler-app-shell'"));
    }

    @Test
    void wrapperRunsTheJourneyScriptDirectly() throws Exception {
        String cmd = source("scripts/butler-manager-journey-acceptance.cmd");

        assertTrue(cmd.contains("butler-manager-journey-acceptance.ps1"));
        assertTrue(cmd.contains("%*"));
        assertTrue(cmd.contains("exit /b %RC%"));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IOException("BF-885 test could not locate " + relativePath);
    }
}
