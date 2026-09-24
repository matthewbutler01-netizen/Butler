package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPlayerDiscoveryBf922Test {

    @Test
    void playerSearchExposesQuickPositionQueriesWithoutNewSearchContract() throws Exception {
        String transform = source("scripts/butler-app-bf922-player-discovery-transform.ps1");

        assertTrue(transform.contains("Quick position searches"));
        assertTrue(transform.contains("href=\"/players?q=QB\">QB</a>"));
        assertTrue(transform.contains("href=\"/players?q=RB\">RB</a>"));
        assertTrue(transform.contains("href=\"/players?q=WR\">WR</a>"));
        assertTrue(transform.contains("href=\"/players?q=TE\">TE</a>"));
    }

    @Test
    void playerHubUsesLoadedPositionForMorePlayerDiscovery() throws Exception {
        String transform = source("scripts/butler-app-bf922-player-discovery-transform.ps1");

        assertTrue(transform.contains(
                "$positionHref = [System.Uri]::EscapeDataString([string]$View.Position)"));
        assertTrue(transform.contains(
                "href=\"/players?q=$positionHref\">Find more $(ConvertTo-HtmlText $View.Position)</a>"));
    }

    @Test
    void compareFirstPlayerUsesLoadedPositionForSecondPlayerSearch() throws Exception {
        String transform = source("scripts/butler-app-bf922-player-discovery-transform.ps1");

        assertTrue(transform.contains(
                "$leftPositionHref = [System.Uri]::EscapeDataString([string]$SelectedLeft.Position)"));
        assertTrue(transform.contains(
                "href=\"/compare?left=$leftHref&q=$leftPositionHref\">Find same-position players</a>"));
    }

    @Test
    void stagingRunsDiscoveryAfterFranchiseScoutBeforeRecoveryPolish() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf918 = staging.indexOf("& $bf918CoreTransform -CorePath $stagedCore");
        int bf922 = staging.indexOf("& $bf922CoreTransform -CorePath $stagedCore");
        int bf884 = staging.indexOf("& $bf884CoreTransform -CorePath $stagedCore");

        assertTrue(bf918 >= 0, "BF-918 staging marker missing");
        assertTrue(bf922 > bf918, "BF-922 must run after Franchise Scout");
        assertTrue(bf884 > bf922, "BF-884 must remain after BF-922");
        assertTrue(staging.contains("butler-app-bf922-player-discovery-transform.ps1"));
    }

    @Test
    void managerJourneyFollowsSamePositionPlayerSearch() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains("Player position discovery"));
        assertTrue(journey.contains("BF-922 FAILED: Player Detail did not render an exact same-position Player Search shortcut."));
        assertTrue(journey.contains("Search results"));
    }

    @Test
    void transformRemainsPresentationOnlyAndAscii() throws Exception {
        String transform = source("scripts/butler-app-bf922-player-discovery-transform.ps1");

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
        throw new IOException("BF-922 test could not locate " + relativePath);
    }
}
