package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardMatchupRoutingBf843Test {

    @Test
    void routingTransformStagesAfterFinalDashboardVisualPass() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf837 = staging.indexOf("& $bf837DashboardTransform -DashboardPath $DashboardPath");
        int bf843 = staging.indexOf("& $bf843DashboardTransform -DashboardPath $DashboardPath");

        assertTrue(bf837 >= 0, "BF-837 final Dashboard visual pass must remain present");
        assertTrue(bf843 > bf837, "BF-843 Dashboard routing must stage after BF-837");
        assertTrue(staging.contains("butler-dashboard-bf843-matchup-routing-transform.ps1"));
    }

    @Test
    void lineupQueueRoutesWeeklyDecisionsToMatchup() throws Exception {
        String transform = source("scripts/butler-dashboard-bf843-matchup-routing-transform.ps1");

        for (String marker : new String[]{
                "\"NOT REVIEWED\"",
                "\"REFRESH AUTOFILL\"",
                "\"AUTOFILL READY\"",
                "\"NO CHANGES\"",
                "\"EVIDENCE GAP\"",
                "$actionHref = \"/matchup/autofill\"",
                "$actionHref = \"/matchup\"",
                "$actionLabel = \"Review Matchup\"",
                "$actionLabel = \"Refresh Lineup\"",
                "$actionLabel = \"View Matchup\""
        }) {
            assertTrue(transform.contains(marker), "BF-843 queue routing missing " + marker);
        }

        assertFalse(transform.contains("\"NEEDS ATTENTION\" {"));
    }

    @Test
    void priorityOneNextActionUsesSameMatchupRouting() throws Exception {
        String transform = source("scripts/butler-dashboard-bf843-matchup-routing-transform.ps1");

        assertTrue(transform.contains("$primaryNextActionHref = \"/matchup/autofill\""));
        assertTrue(transform.contains("$primaryNextActionHref = \"/matchup\""));
        assertTrue(transform.contains("$primaryNextActionLabel = \"Review Matchup\""));
        assertTrue(transform.contains("$primaryNextActionLabel = \"Refresh Lineup\""));
        assertTrue(transform.contains("$primaryNextActionLabel = \"View Matchup\""));
    }

    @Test
    void myTeamRemainsSecondaryAndRosterRepairPath() throws Exception {
        String managerProof = source("scripts/butler-dashboard-bf819-manager-proof-mode-transform.ps1");
        String transform = source("scripts/butler-dashboard-bf843-matchup-routing-transform.ps1");

        assertTrue(managerProof.contains("$secondaryActions = '<a class=\"command-button secondary\" href=\"/team\">My Team</a>'"));
        assertTrue(managerProof.contains("\"NEEDS ATTENTION\" = @("));
        assertTrue(managerProof.contains("\"/team\", \"Review My Team\""));
        assertFalse(transform.contains("$secondaryActions ="));
        assertFalse(transform.contains("Review My Team"));
    }

    @Test
    void routingOverlayAddsNoProviderOrWriteBehavior() throws Exception {
        String transform = source("scripts/butler-dashboard-bf843-matchup-routing-transform.ps1");

        assertFalse(transform.contains("Invoke-RestMethod"));
        assertFalse(transform.contains("Invoke-WebRequest"));
        assertFalse(transform.contains("https://api.sleeper.app"));
        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("submitTransaction"));
        assertFalse(transform.contains("setFaab"));
        assertFalse(transform.contains("AutoFillLineupOptimizer"));
        assertTrue(transform.contains("System.Management.Automation.Language.Parser"));
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
        throw new IOException("BF-843 test could not locate " + relativePath);
    }
}
