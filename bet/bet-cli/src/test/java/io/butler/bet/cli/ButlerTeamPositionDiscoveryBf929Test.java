package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTeamPositionDiscoveryBf929Test {

    @Test
    void positionCardsReuseExactExistingPlayerSearchQuery() throws Exception {
        String transform = source("scripts/butler-app-bf929-team-position-discovery-transform.ps1");

        assertTrue(transform.contains(
                "$positionHref = [System.Uri]::EscapeDataString([string]$position.Position)"));
        assertTrue(transform.contains("/players?q=$positionHref"));
        assertTrue(transform.contains("Browse $(ConvertTo-HtmlText $position.Position) players"));
        assertTrue(transform.contains("Starter coverage"));
        assertTrue(transform.contains("Total position value"));
        assertTrue(transform.contains("Unavailable"));
    }

    @Test
    void stagingRunsAfterExactLeagueTradePartnerBeforeRecoveryPolish() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf927 = staging.indexOf("& $bf927CoreTransform -CorePath $stagedCore");
        int bf929 = staging.indexOf("& $bf929CoreTransform -CorePath $stagedCore");
        int bf884 = staging.indexOf("& $bf884CoreTransform -CorePath $stagedCore");

        assertTrue(bf927 >= 0, "BF-927 staging marker missing");
        assertTrue(bf929 > bf927, "BF-929 must run after exact League trade partner");
        assertTrue(bf884 > bf929, "BF-884 must remain after BF-929");
        assertTrue(staging.contains("butler-app-bf929-team-position-discovery-transform.ps1"));
    }

    @Test
    void managerJourneyFollowsExactQbDiscoveryRoute() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains("href=\"/players?q=QB\">Browse QB players</a>"));
        assertTrue(journey.contains("href=\"/players?q=RB\">Browse RB players</a>"));
        assertTrue(journey.contains("href=\"/players?q=WR\">Browse WR players</a>"));
        assertTrue(journey.contains("href=\"/players?q=TE\">Browse TE players</a>"));
        assertTrue(journey.contains("BF-929 FAILED: My Team did not expose the exact QB Player Search route."));
        assertTrue(journey.contains("My Team position discovery"));
        assertTrue(journey.contains("value=\"QB\""));
    }

    @Test
    void transformStaysPresentationOnlyAndAscii() throws Exception {
        String transform = source("scripts/butler-app-bf929-team-position-discovery-transform.ps1");

        assertFalse(transform.contains("Invoke-RestMethod"));
        assertFalse(transform.contains("Invoke-WebRequest"));
        assertFalse(transform.contains("Invoke-ButlerReadOnly"));
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
        throw new IOException("BF-929 test could not locate " + relativePath);
    }
}
