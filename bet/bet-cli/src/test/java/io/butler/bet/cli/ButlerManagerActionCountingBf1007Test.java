package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerManagerActionCountingBf1007Test {

    @Test
    void snapshotPersistsManagerMoveCountSeparatelyFromChangedAssignments() throws Exception {
        String source = source("scripts/butler-bf808-autofill-command-center-transform.ps1");
        assertTrue(source.contains("ManagerMoveCount = [int]$managerMoveCount"));
        assertTrue(source.contains("PromotionCount = [int]$promotionCount"));
        assertTrue(source.contains("BenchMoveCount = [int]$benchMoveCount"));
        assertTrue(source.contains("ChangedCount = [int]$changedCount"));
    }

    @Test
    void matchupAndAdvisorUseManagerMovesForDecisionFacingCopy() throws Exception {
        String matchup = source("scripts/butler-app-bf881-matchup-decision-first-transform.ps1");
        String advisor = source("scripts/butler-app-bf943-changes-first-lineup-transform.ps1");

        assertTrue(matchup.contains("Title = \"Make $managerMoveCount lineup $moveWord\""));
        assertTrue(matchup.contains("these are not separate manager moves"));
        assertTrue(advisor.contains("Review $managerMoveCount lineup $moveNoun"));
        assertTrue(advisor.contains("optimizer slot placement"));
        assertTrue(advisor.contains("Manager moves"));
    }

    @Test
    void finalQualificationAndAcceptanceUseReviewWording() throws Exception {
        String qualifier = source("scripts/butler-app-bf946-lineup-matchup-return-transform.ps1");
        String acceptance = source("scripts/butler-bf1007-manager-action-counting-acceptance.ps1");

        assertTrue(qualifier.contains("Title = \"Review $managerMoveCount lineup $moveWord\""));
        assertTrue(acceptance.contains("Title = \"Review $managerMoveCount lineup $moveWord\""));
        assertTrue(acceptance.contains("Review 1 lineup move"));

        String healthy = source("scripts/butler-dashboard-bf816-healthy-state-polish-transform.ps1");
        assertTrue(healthy.contains("Lineup review complete; no change proven"));
        assertTrue(acceptance.contains("Lineup review complete; no change proven"));
    }

    @Test
    void savedReviewUsesManagerMoveMetric() throws Exception {
        String saved = source("scripts/butler-app-bf1005-matchup-saved-lineup-review-transform.ps1");
        assertTrue(saved.contains("Manager moves</strong>"));
        assertTrue(saved.contains("$savedManagerMoveCount"));
        assertTrue(saved.contains("these are not separate manager moves"));
    }

    @Test
    void presentationCloseoutIncludesBf1007() throws Exception {
        String closeout = source("scripts/butler-presentation-closeout-acceptance.ps1");
        assertTrue(closeout.contains("Id = 'BF-1007'"));
        assertTrue(closeout.contains("butler-bf1007-manager-action-counting-acceptance.ps1"));
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
        throw new IOException("BF-1007 test could not locate " + relativePath);
    }
}
