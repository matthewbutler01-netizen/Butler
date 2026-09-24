package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerMatchupOpponentActionsBf926Test {

    @Test
    void confirmedMatchupExposesExactOpponentScoutAndTradeActions() throws Exception {
        String transform = source("scripts/butler-app-bf926-matchup-opponent-actions-transform.ps1");

        assertTrue(transform.contains(
                "$opponentHrefId = [System.Uri]::EscapeDataString([string]$Matchup.OpponentTeamId)"));
        assertTrue(transform.contains(
                "href=\"/team\">Open My Team</a>"));
        assertTrue(transform.contains(
                "href=\"/franchise?id=$opponentHrefId\">Scout opponent</a>"));
        assertTrue(transform.contains(
                "href=\"/trade?opponent=$opponentHrefId\">Trade with opponent</a>"));
    }

    @Test
    void unavailableMatchupDoesNotInventOpponentActions() throws Exception {
        String transform = source("scripts/butler-app-bf926-matchup-opponent-actions-transform.ps1");

        String unavailableNew = slice(
                transform,
                "$unavailableHeroNew = '",
                "'\n$core = Replace-ExactlyOnce -Text $core -Old $unavailableHeroOld");

        assertTrue(unavailableNew.contains("href=\"/team\">Open My Team</a>"));
        assertFalse(unavailableNew.contains("Scout opponent"));
        assertFalse(unavailableNew.contains("Trade with opponent"));
    }

    @Test
    void stagingRunsAfterLeagueActionsBeforeRecoveryPolish() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf924 = staging.indexOf("& $bf924CoreTransform -CorePath $stagedCore");
        int bf926 = staging.indexOf("& $bf926CoreTransform -CorePath $stagedCore");
        int bf884 = staging.indexOf("& $bf884CoreTransform -CorePath $stagedCore");

        assertTrue(bf924 >= 0, "BF-924 staging marker missing");
        assertTrue(bf926 > bf924, "BF-926 must run after League Hub action polish");
        assertTrue(bf884 > bf926, "BF-884 must remain after BF-926");
        assertTrue(staging.contains("butler-app-bf926-matchup-opponent-actions-transform.ps1"));
    }

    @Test
    void managerJourneyExercisesExactOpponentPaths() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains("Matchup opponent Franchise Scout"));
        assertTrue(journey.contains("Matchup opponent Trade Analyzer"));
        assertTrue(journey.contains("BF-926 FAILED: confirmed Matchup did not expose exact opponent Scout + Trade actions."));
        assertTrue(journey.contains(
                "href=\"(?<href>/franchise\\?id[^\"]*)\">Scout opponent</a>")
                || journey.contains(
                "href=\"(?<href>/franchise\\?id=[^\"]+)\">Scout opponent</a>"));
        assertTrue(journey.contains(
                "href=\"(?<href>/trade\\?opponent=[^\"]+)\">Trade with opponent</a>"));
    }

    @Test
    void transformRemainsPresentationOnlyAndAscii() throws Exception {
        String transform = source("scripts/butler-app-bf926-matchup-opponent-actions-transform.ps1");

        int safetyScan = transform.indexOf("$presentation -match");
        assertTrue(safetyScan > 0, "BF-926 safety scan must remain present");
        String operational = transform.substring(0, safetyScan);

        assertFalse(operational.contains("Invoke-RestMethod"));
        assertFalse(operational.contains("Invoke-WebRequest"));
        assertFalse(operational.contains("Method = \"POST\""));
        assertFalse(operational.contains("submitTransaction"));
        assertFalse(operational.contains("setFaab"));
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(transform));
    }

    private static String slice(String source, String startMarker, String endMarker) {
        String normalized = source.replace("\r\n", "\n");
        int start = normalized.indexOf(startMarker);
        assertTrue(start >= 0, "missing start marker " + startMarker);
        start += startMarker.length();
        int end = normalized.indexOf(endMarker, start);
        assertTrue(end > start, "missing end marker " + endMarker);
        return normalized.substring(start, end);
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
        throw new IOException("BF-926 test could not locate " + relativePath);
    }
}
