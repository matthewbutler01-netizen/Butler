package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPlayerSearchWaiverBridgeBf930Test {

    @Test
    void playerSearchAddsWaiverBoardBesideExistingSafeReturns() throws Exception {
        String transform = source("scripts/butler-app-bf930-player-search-waiver-bridge-transform.ps1");

        assertTrue(transform.contains("function ConvertTo-PlayerSearchHtml {"));
        assertTrue(transform.contains("href=\"/team\">Back to My Team</a>"));
        assertTrue(transform.contains("href=\"/league\">Back to League</a>"));
        assertTrue(transform.contains("href=\"/waivers\">Check Waiver Board</a>"));
        assertTrue(transform.contains("Free agents remain on Waiver Board."));
    }

    @Test
    void stagingRunsAfterTeamPositionDiscoveryBeforeRecoveryPolish() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf929 = staging.indexOf("& $bf929CoreTransform -CorePath $stagedCore");
        int bf930 = staging.indexOf("& $bf930CoreTransform -CorePath $stagedCore");
        int bf884 = staging.indexOf("& $bf884CoreTransform -CorePath $stagedCore");

        assertTrue(bf929 >= 0, "BF-929 staging marker missing");
        assertTrue(bf930 > bf929, "BF-930 must run after BF-929");
        assertTrue(bf884 > bf930, "BF-884 must remain after BF-930");
        assertTrue(staging.contains("butler-app-bf930-player-search-waiver-bridge-transform.ps1"));
    }

    @Test
    void managerJourneyFollowsWaiverBoardBridge() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains("BF-930 FAILED: Player Search did not expose the Waiver Board bridge."));
        assertTrue(journey.contains("Player Search direct Waiver Board"));
        assertTrue(journey.contains(
                "href=\"(?<href>/waivers)\">Check Waiver Board</a>"));
        assertTrue(journey.contains("Butler waiver decision"));
        assertTrue(journey.contains("Players Butler authorized for review"));
    }

    @Test
    void transformStaysNavigationOnlyAndAscii() throws Exception {
        String transform = source("scripts/butler-app-bf930-player-search-waiver-bridge-transform.ps1");

        assertFalse(transform.contains("Invoke-RestMethod"));
        assertFalse(transform.contains("Invoke-WebRequest"));
        assertFalse(transform.contains("Invoke-ButlerReadOnly"));
        assertFalse(transform.contains("https://api.sleeper.app"));
        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("setFaab"));
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
        throw new IOException("BF-930 test could not locate " + relativePath);
    }
}
