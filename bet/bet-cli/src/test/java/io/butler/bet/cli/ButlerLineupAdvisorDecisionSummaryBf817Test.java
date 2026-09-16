package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLineupAdvisorDecisionSummaryBf817Test {

    @Test
    void lineupAdvisorCoversIdleGapChangedAndNoChangeStates() throws Exception {
        String transform = source("scripts/butler-app-bf817-lineup-advisor-transform.ps1");

        assertTrue(transform.contains("This week's lineup decision"));
        assertTrue(transform.contains("NOT REVIEWED"));
        assertTrue(transform.contains("Lineup decision blocked by an evidence gap"));
        assertTrue(transform.contains("EVIDENCE GAP"));
        assertTrue(transform.contains("Make $changedCount lineup $changeWord"));
        assertTrue(transform.contains("CHANGES FOUND"));
        assertTrue(transform.contains("Keep the current lineup"));
        assertTrue(transform.contains("NO CHANGES"));
    }

    @Test
    void changedLineupSummarizesDecisionWhyStartAndSitWithoutInventingDeltas() throws Exception {
        String transform = source("scripts/butler-app-bf817-lineup-advisor-transform.ps1");

        assertTrue(transform.contains("<h3>Decision</h3>"));
        assertTrue(transform.contains("<h3>Why</h3>"));
        assertTrue(transform.contains("<h3>Start</h3>"));
        assertTrue(transform.contains("<h3>Sit</h3>"));
        assertTrue(transform.contains("$promotionText"));
        assertTrue(transform.contains("$benchText"));
        assertTrue(transform.contains("Butler is not claiming a separate per-player delta"));
        assertTrue(transform.contains("Projected change"));
    }

    @Test
    void evidenceGapPreservesExactReasonAndReadOnlyRecovery() throws Exception {
        String transform = source("scripts/butler-app-bf817-lineup-advisor-transform.ps1");

        assertTrue(transform.contains("$(ConvertTo-HtmlText $AutoFill.Reason)"));
        assertTrue(transform.contains("Retry only after the missing projection or provider evidence becomes available."));
        assertTrue(transform.contains("Butler did not submit a lineup to Sleeper."));
    }

    @Test
    void existingDetailedLineupAndRosterBoardsRemainAvailable() throws Exception {
        String transform = source("scripts/butler-app-bf817-lineup-advisor-transform.ps1");

        assertTrue(transform.contains("lineup-board"));
        assertTrue(transform.contains("Promote to lineup"));
        assertTrue(transform.contains("Move to bench"));
        assertTrue(transform.contains("Players by lineup state"));
    }

    @Test
    void bf816StagesBf817AndBf817RemainsPresentationOnly() throws Exception {
        String bf816 = source("scripts/butler-dashboard-bf816-healthy-state-polish-transform.ps1");
        String transform = source("scripts/butler-app-bf817-lineup-advisor-transform.ps1");

        assertTrue(bf816.contains("butler-app-bf817-lineup-advisor-transform.ps1"));
        assertTrue(bf816.contains("& $bf817Transform -CorePath $stagedCore"));

        assertFalse(transform.contains("BUTLER_FANTASYPROS_API_KEY"));
        assertFalse(transform.contains("AutoFillLineupOptimizer"));
        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("Invoke-RestMethod"));
        assertFalse(transform.contains("$env:"));
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
        throw new IOException("BF-817 test could not locate " + relativePath);
    }
}
