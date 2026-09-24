package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueFranchiseActionsBf924Test {

    @Test
    void franchiseCardsExposeExactScoutAndSafeTradeEntry() throws Exception {
        String transform = source("scripts/butler-app-bf924-league-franchise-actions-transform.ps1");

        assertTrue(transform.contains(
                "$leaderHrefId = [System.Uri]::EscapeDataString([string]$leader.TeamId)"));
        assertTrue(transform.contains(
                "href=\"/franchise?id=$leaderHrefId\">Scout franchise</a>"));
        assertTrue(transform.contains(
                "href=\"/trade\">Open Trade Analyzer</a>"));
        assertFalse(transform.contains("/trade?opponent=$leaderHrefId"));
    }

    @Test
    void stagingRunsAfterCompareLoopBeforeRecoveryPolish() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf923 = staging.indexOf("& $bf923CoreTransform -CorePath $stagedCore");
        int bf924 = staging.indexOf("& $bf924CoreTransform -CorePath $stagedCore");
        int bf884 = staging.indexOf("& $bf884CoreTransform -CorePath $stagedCore");

        assertTrue(bf923 >= 0, "BF-923 staging marker missing");
        assertTrue(bf924 > bf923, "BF-924 must run after Player Compare loop");
        assertTrue(bf884 > bf924, "BF-884 must remain after BF-924");
        assertTrue(staging.contains("butler-app-bf924-league-franchise-actions-transform.ps1"));
    }

    @Test
    void managerJourneyExercisesBothLeagueCardActions() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains("League direct Trade Analyzer"));
        assertTrue(journey.contains("League direct Trade Analyzer workspace"));
        assertTrue(journey.contains("Opening Trade Analyzer..."));
        assertTrue(journey.contains("content=\"1;url=/trade?load=1\""));
        assertTrue(journey.contains("League direct Franchise Scout"));
        assertTrue(journey.contains(
                "href=\"(?<href>/franchise\\?id=[^\"]+)\">Scout franchise</a>"));
        assertTrue(journey.contains(
                "href=\"(?<href>/trade)\">Open Trade Analyzer</a>"));
    }

    @Test
    void transformStaysPresentationOnlyAndAscii() throws Exception {
        String transform = source("scripts/butler-app-bf924-league-franchise-actions-transform.ps1");

        assertFalse(transform.contains("Invoke-RestMethod"));
        assertFalse(transform.contains("Invoke-WebRequest"));
        assertFalse(transform.contains("https://api.sleeper.app"));
        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("submitTransaction"));
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(transform));
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
        throw new IOException("BF-924 test could not locate " + relativePath);
    }
}
