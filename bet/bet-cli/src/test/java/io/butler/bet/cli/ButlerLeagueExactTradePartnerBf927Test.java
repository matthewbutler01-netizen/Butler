package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueExactTradePartnerBf927Test {

    @Test
    void leagueTradeActionReusesExactEscapedFranchiseId() throws Exception {
        String transform = source("scripts/butler-app-bf927-league-exact-trade-partner-transform.ps1");

        assertTrue(transform.contains(
                "$leaderHrefId = [System.Uri]::EscapeDataString([string]$leader.TeamId)"));
        assertTrue(transform.contains(
                "href=\"/franchise?id=$leaderHrefId\">Scout franchise</a>"));
        assertTrue(transform.contains(
                "href=\"/trade?opponent=$leaderHrefId\">Open Trade Analyzer</a>"));
        assertTrue(transform.contains(
                "href=\"/trade\">Open Trade Analyzer</a>"));
    }

    @Test
    void stagingRunsAfterMatchupActionsBeforeRecoveryPolish() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf926 = staging.indexOf("& $bf926CoreTransform -CorePath $stagedCore");
        int bf927 = staging.indexOf("& $bf927CoreTransform -CorePath $stagedCore");
        int bf884 = staging.indexOf("& $bf884CoreTransform -CorePath $stagedCore");

        assertTrue(bf926 >= 0, "BF-926 staging marker missing");
        assertTrue(bf927 > bf926, "BF-927 must run after Matchup opponent actions");
        assertTrue(bf884 > bf927, "BF-884 must remain after BF-927");
        assertTrue(staging.contains("butler-app-bf927-league-exact-trade-partner-transform.ps1"));
    }

    @Test
    void managerJourneyProvesLeagueScoutAndTradeKeepSameFranchise() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains("BF-927 FAILED: League franchise actions changed team context."));
        assertTrue(journey.contains("League exact Trade Analyzer"));
        assertTrue(journey.contains(
                "href=\"(?<href>/franchise\\?id=[^\"]+)\">Scout franchise</a>"));
        assertTrue(journey.contains(
                "href=\"(?<href>/trade\\?opponent=[^\"]+)\">Open Trade Analyzer</a>"));
        assertTrue(journey.contains(
                "BF-927 FAILED: loaded League Trade Analyzer did not preserve the exact Franchise Scout link."));
    }

    @Test
    void transformRemainsPresentationOnlyAndAscii() throws Exception {
        String transform = source("scripts/butler-app-bf927-league-exact-trade-partner-transform.ps1");

        assertFalse(transform.contains("Invoke-RestMethod"));
        assertFalse(transform.contains("Invoke-WebRequest"));
        assertFalse(transform.contains("https://api.sleeper.app"));
        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("submitTransaction"));
        assertFalse(transform.contains("setFaab"));
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
        throw new IOException("BF-927 test could not locate " + relativePath);
    }
}
