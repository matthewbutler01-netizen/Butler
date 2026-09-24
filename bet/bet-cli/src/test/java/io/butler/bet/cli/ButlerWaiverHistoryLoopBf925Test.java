package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWaiverHistoryLoopBf925Test {

    @Test
    void waiverBoardUsesDirectLoadedHistoryAction() throws Exception {
        String transform = source("scripts/butler-dashboard-bf925-waiver-history-loop-transform.ps1");

        assertTrue(transform.contains(
                "$waiverHistoryLink = '<a class=\"waiver-history-link\" href=\"/history?load=1\">View Decision History</a>'"));
        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("Invoke-RestMethod"));
        assertFalse(transform.contains("Invoke-WebRequest"));
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(transform));
    }

    @Test
    void historyProvidesCurrentWaiverAndDashboardActions() throws Exception {
        String history = source("scripts/butler-decision-history.ps1");

        assertTrue(history.contains("href=\"/waivers\">Review Waiver Board</a>"));
        assertTrue(history.contains("href=\"/\">Back to Dashboard</a>"));
        assertTrue(history.contains("history-actions"));
        assertTrue(history.contains("READ ONLY."));
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(history));
    }

    @Test
    void stagingRunsAfterDecisionFirstWaiverBeforeMobilePolish() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf873 = staging.indexOf("& $bf873DashboardTransform -DashboardPath $DashboardPath");
        int bf925 = staging.indexOf("& $bf925DashboardTransform -DashboardPath $DashboardPath");
        int bf898 = staging.indexOf("& $bf898Transform -DashboardPath $DashboardPath");

        assertTrue(bf873 >= 0, "BF-873 staging marker missing");
        assertTrue(bf925 > bf873, "BF-925 must run after decision-first Waiver Board");
        assertTrue(bf898 > bf925, "BF-898 mobile polish must remain after BF-925");
        assertTrue(staging.contains("butler-dashboard-bf925-waiver-history-loop-transform.ps1"));
    }

    @Test
    void managerJourneyExercisesBothDirections() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains("Waiver Board direct Decision History"));
        assertTrue(journey.contains("Decision History direct Waiver Board"));
        assertTrue(journey.contains(
                "href=\"(?<href>/history\\?load=1)\">View Decision History</a>"));
        assertTrue(journey.contains(
                "href=\"(?<href>/waivers)\">Review Waiver Board</a>"));
    }

    @Test
    void historyLoopDoesNotAddProviderOrWriteBehavior() throws Exception {
        String transform = source("scripts/butler-dashboard-bf925-waiver-history-loop-transform.ps1");
        String history = source("scripts/butler-decision-history.ps1");

        for (String forbidden : new String[]{
                "https://api.sleeper.app",
                "submitTransaction",
                "Method = \"POST\""
        }) {
            assertFalse(transform.contains(forbidden), "transform introduced " + forbidden);
        }

        assertFalse(history.contains("Method = \"POST\""));
        assertFalse(history.contains("sleeperLiveWaiverRecommendationAuditCapture"));
        assertFalse(history.contains("sleeperLiveWaiverSnapshotSync"));
        assertFalse(history.contains("sleeperLiveWaiverMarketAttentionSync"));
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
        throw new IOException("BF-925 test could not locate " + relativePath);
    }
}
