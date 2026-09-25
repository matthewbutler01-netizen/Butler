package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeBackToLeagueBf928Test {

    @Test
    void loadedOpponentActionsExposeScoutAndBackToLeagueTogether() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        assertTrue(trade.contains(
                "$opponentTeamHrefId = [System.Uri]::EscapeDataString([string]$Opponent.TeamId)"));
        assertTrue(trade.contains(
                "href=\`\"/franchise?id=$opponentTeamHrefId\`\">Scout franchise</a>"));
        assertTrue(trade.contains(
                "href=\`\"/league\`\">Back to League</a>"));

        int loadedBlock = trade.indexOf("if ($null -ne $Opponent)");
        int action = trade.indexOf("$opponentScoutAction =", loadedBlock);
        int builder = trade.indexOf("$giveHtml =", action);
        assertTrue(loadedBlock >= 0 && action > loadedBlock && builder > action);
        assertTrue(trade.substring(action, builder).contains("Back to League"));
    }

    @Test
    void managerJourneyFollowsReturnPathAndKeepsUnloadedStateClean() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains("BF-928 FAILED: loaded Trade Analyzer did not expose Back to League."));
        assertTrue(journey.contains("Trade Analyzer back to League"));
        assertTrue(journey.contains(
                "href=\"(?<href>/league)\">Back to League</a>"));
        assertTrue(journey.contains(
                "Trade Analyzer unloaded opponent actions"));
        assertTrue(journey.contains(
                "Back to League</a>"));
    }

    @Test
    void tradeLabRemainsGetOnlyReadOnlyAndAscii() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        assertFalse(trade.contains("Method = \"POST\""));
        assertFalse(trade.contains("Invoke-RestMethod"));
        assertFalse(trade.contains("Invoke-WebRequest"));
        assertFalse(trade.contains("https://api.sleeper.app"));
        assertTrue(trade.contains("READ ONLY"));
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(trade));
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
        throw new IOException("BF-928 test could not locate " + relativePath);
    }
}
