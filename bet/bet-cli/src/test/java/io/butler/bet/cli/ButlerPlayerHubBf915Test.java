package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPlayerHubBf915Test {

    @Test
    void playerHubPutsManagerSnapshotAndDecisionShortcutsAboveEvidence() throws Exception {
        String transform = source("scripts/butler-app-bf915-player-hub-transform.ps1");

        assertTrue(transform.contains("Player snapshot"));
        assertTrue(transform.contains("<h2>Player hub</h2>"));
        assertTrue(transform.contains("What do you want to decide?"));
        assertTrue(transform.contains("Position"));
        assertTrue(transform.contains("Roster slot"));
        assertTrue(transform.contains("Age"));
        assertTrue(transform.contains("Games"));
        assertTrue(transform.contains("Age context"));
    }

    @Test
    void playerHubRoutesIntoExistingButlerDecisionWorkflows() throws Exception {
        String transform = source("scripts/butler-app-bf915-player-hub-transform.ps1");

        assertTrue(transform.contains("href=\"/matchup\">Review Matchup</a>"));
        assertTrue(transform.contains("href=\"/compare?left=$hrefId\">Compare this player</a>"));
        assertTrue(transform.contains("href=\"/trade\">Open Trade Analyzer</a>"));
        assertTrue(transform.contains("href=\"/waivers\">Check Waiver Board</a>"));
        assertTrue(transform.contains("This profile stays neutral and does not create a recommendation by itself."));
    }

    @Test
    void playerHubReusesExistingDetailViewWithoutNewReadOrWriteBehavior() throws Exception {
        String transform = source("scripts/butler-app-bf915-player-hub-transform.ps1");
        int start = transform.indexOf("$installedStart =");
        assertTrue(start > 0);
        String operational = transform.substring(0, start);

        assertTrue(operational.contains("Add-PlayerHubPresentation -Html $html -View $playerDetail"));
        assertFalse(operational.contains("Invoke-RestMethod"));
        assertFalse(operational.contains("Invoke-WebRequest"));
        assertFalse(operational.contains("Invoke-ButlerReadOnly"));
        assertFalse(operational.contains("Invoke-Bf742DashboardWorkerRead"));
        assertFalse(operational.contains("Method = \"POST\""));
        assertFalse(operational.contains("submitTransaction"));
        assertFalse(operational.contains("setFaab"));
    }

    @Test
    void stagingRunsPlayerHubAfterCompareBeforeTeamHub() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf906 = staging.indexOf("& $bf906CoreTransform -CorePath $stagedCore");
        int bf915 = staging.indexOf("& $bf915CoreTransform -CorePath $stagedCore");
        int bf908 = staging.indexOf("& $bf908CoreTransform -CorePath $stagedCore");

        assertTrue(bf906 >= 0, "BF-906 staging marker missing");
        assertTrue(bf915 > bf906, "BF-915 must run after Player Compare");
        assertTrue(bf908 > bf915, "BF-908 must remain after Player Hub");
        assertTrue(staging.contains("butler-app-bf915-player-hub-transform.ps1"));
    }

    @Test
    void managerJourneyRequiresPlayerHubMarkers() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains(
                "Markers @('Player Detail','Player snapshot','What do you want to decide?','Open Trade Analyzer','Check Waiver Board','Back to My Team','Player Search','READ ONLY')"));
    }

    @Test
    void transformSourceIsAsciiOnly() throws Exception {
        String transform = source("scripts/butler-app-bf915-player-hub-transform.ps1");
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
        throw new IOException("BF-915 test could not locate " + relativePath);
    }
}
