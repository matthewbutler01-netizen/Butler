package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerFranchiseScoutBf918Test {

    @Test
    void franchiseScoutPutsSnapshotAndManagerActionsBeforeEvidence() throws Exception {
        String transform = source("scripts/butler-app-bf918-franchise-scout-transform.ps1");

        assertTrue(transform.contains("Franchise snapshot"));
        assertTrue(transform.contains("What this team owns"));
        assertTrue(transform.contains("Scout this franchise"));
        assertTrue(transform.contains("Evidence quality"));
    }

    @Test
    void franchiseScoutRoutesIntoExistingButlerWorkflows() throws Exception {
        String transform = source("scripts/butler-app-bf918-franchise-scout-transform.ps1");

        assertTrue(transform.contains("href=\"/trade\">Open Trade Analyzer</a>"));
        assertTrue(transform.contains("href=\"/players\">Find a player</a>"));
        assertTrue(transform.contains("href=\"/compare\">Compare players</a>"));
        assertTrue(transform.contains("href=\"/league\">Back to League</a>"));
    }

    @Test
    void franchiseScoutReusesExistingDetailViewWithoutNewReadOrWriteBehavior() throws Exception {
        String transform = source("scripts/butler-app-bf918-franchise-scout-transform.ps1");
        int start = transform.indexOf("$installedStart =");
        assertTrue(start > 0);
        String operational = transform.substring(0, start);

        assertTrue(operational.contains("Add-FranchiseScoutPresentation -Html $html -View $franchiseDetail"));
        assertFalse(operational.contains("Invoke-RestMethod"));
        assertFalse(operational.contains("Invoke-WebRequest"));
        assertFalse(operational.contains("Invoke-ButlerReadOnly"));
        assertFalse(operational.contains("Invoke-Bf742DashboardWorkerRead"));
        assertFalse(operational.contains("Method = \"POST\""));
        assertFalse(operational.contains("submitTransaction"));
        assertFalse(operational.contains("setFaab"));
    }

    @Test
    void stagingRunsFranchiseScoutAfterLeagueHubBeforeManagerRecovery() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf909 = staging.indexOf("& $bf909CoreTransform -CorePath $stagedCore");
        int bf918 = staging.indexOf("& $bf918CoreTransform -CorePath $stagedCore");
        int bf884 = staging.indexOf("& $bf884CoreTransform -CorePath $stagedCore");

        assertTrue(bf909 >= 0, "BF-909 staging marker missing");
        assertTrue(bf918 > bf909, "BF-918 must run after League Hub");
        assertTrue(bf884 > bf918, "BF-884 must remain after Franchise Scout");
        assertTrue(staging.contains("butler-app-bf918-franchise-scout-transform.ps1"));
    }

    @Test
    void managerJourneyRequiresFranchiseScoutMarkers() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains(
                "Markers @('Franchise Detail','Franchise snapshot','Scout this franchise','Open Trade Analyzer','Back to League','READ ONLY')"));
    }

    @Test
    void transformSourceIsAsciiOnly() throws Exception {
        String transform = source("scripts/butler-app-bf918-franchise-scout-transform.ps1");
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
        throw new IOException("BF-918 test could not locate " + relativePath);
    }
}
