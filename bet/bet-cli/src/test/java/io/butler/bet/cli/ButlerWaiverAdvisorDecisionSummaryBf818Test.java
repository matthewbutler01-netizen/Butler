package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWaiverAdvisorDecisionSummaryBf818Test {

    @Test
    void waiverAdvisorCoversGovernedManagerStates() throws Exception {
        String transform = source("scripts/butler-dashboard-bf818-waiver-advisor-transform.ps1");

        assertTrue(transform.contains("Waiver Advisor"));
        assertTrue(transform.contains("Make this waiver move"));
        assertTrue(transform.contains("MOVE READY"));
        assertTrue(transform.contains("Refresh before acting"));
        assertTrue(transform.contains("REFRESH FIRST"));
        assertTrue(transform.contains("No waiver move proven"));
        assertTrue(transform.contains("NO MOVE"));
        assertTrue(transform.contains("Waiver move is pending"));
        assertTrue(transform.contains("Waiver move complete"));
        assertTrue(transform.contains("Waiver decision blocked by stale evidence"));
        assertTrue(transform.contains("DO NOT ACT"));
        assertTrue(transform.contains("Waiver decision unavailable"));
    }

    @Test
    void activeMoveUsesOnlyAlreadyReconciledPair() throws Exception {
        String transform = source("scripts/butler-dashboard-bf818-waiver-advisor-transform.ps1");

        assertTrue(transform.contains("if (-not $pair.Active)"));
        assertTrue(transform.contains("$pair.Add.Name"));
        assertTrue(transform.contains("$pair.Drop.Name"));
        assertTrue(transform.contains("reconciled BF-652 ADD/DROP pair"));
        assertTrue(transform.contains("make the transaction manually in Sleeper"));
    }

    @Test
    void noMoveIsACompletedNoActionOutcome() throws Exception {
        String transform = source("scripts/butler-dashboard-bf818-waiver-advisor-transform.ps1");

        assertTrue(transform.contains("Hold. Butler has no add/drop move to recommend from this evidence frame."));
        assertTrue(transform.contains("valid no-action result, not missing recommendation data"));
        assertTrue(transform.contains("No waiver action is needed from this decision"));
    }

    @Test
    void existingBoardAndCandidateDetailRemainBelowAdvisor() throws Exception {
        String transform = source("scripts/butler-dashboard-bf818-waiver-advisor-transform.ps1");

        assertTrue(transform.contains("$header\n$waiverAdvisor\n<section class=\"panel\">"));
        assertTrue(transform.contains("Current governed ADD"));
        assertTrue(transform.contains("Paired audited DROP"));
        assertTrue(transform.contains("NOT A RANKING"));
        assertTrue(transform.contains("function ConvertTo-WaiverCandidateDetailHtml"));
    }

    @Test
    void bf817StagesBf818AndBf818GuardsPresentationBoundary() throws Exception {
        String bf817 = source("scripts/butler-app-bf817-lineup-advisor-transform.ps1");
        String transform = source("scripts/butler-dashboard-bf818-waiver-advisor-transform.ps1");

        assertTrue(bf817.contains("butler-dashboard-bf818-waiver-advisor-transform.ps1"));
        assertTrue(bf817.contains("& $bf818Transform -DashboardPath $stagedDashboard"));
        assertTrue(transform.contains("if ($setupNew -match 'Invoke-RestMethod|Invoke-ButlerReadOnly|Method = \"POST\"|sleeperLiveWaiverFinalRecommendationBundle|BF-641')"));
        assertTrue(transform.contains("Butler did not submit, cancel, or replace a Sleeper waiver transaction from this page."));
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
        throw new IOException("BF-818 test could not locate " + relativePath);
    }
}
