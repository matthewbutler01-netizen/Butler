package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerUnifiedDecisionPackageBf815Test {

    @Test
    void decisionPackageSummarizesTheSamePriorityOneState() throws Exception {
        String transform = source("scripts/butler-dashboard-bf815-unified-decision-package-transform.ps1");

        assertTrue(transform.contains("Priority 01 decision package"));
        assertTrue(transform.contains("<strong>Decision</strong>"));
        assertTrue(transform.contains("<strong>Trust frame</strong>"));
        assertTrue(transform.contains("<strong>Next action</strong>"));
        assertTrue(transform.contains("<strong>Record</strong>"));
        assertTrue(transform.contains("$decisionPackageKind = [string]$priorityOne.Kind"));
        assertTrue(transform.contains("$decisionPackageStatus = [string]$priorityOne.Status"));
        assertTrue(transform.contains("$decisionPackageAction = [string]$primaryNextActionLabel"));
        assertTrue(transform.contains("$decisionPackageHtml"));
    }

    @Test
    void lineupEvidenceGapRemainsTruthfulAndReadOnly() throws Exception {
        String transform = source("scripts/butler-dashboard-bf815-unified-decision-package-transform.ps1");

        assertTrue(transform.contains("\"EVIDENCE GAP\""));
        assertTrue(transform.contains("Projection evidence incomplete"));
        assertTrue(transform.contains("AutoFill result saved locally"));
        assertTrue(transform.contains("Manager action; Butler remains read-only"));
        assertTrue(transform.contains("REFRESH AUTOFILL"));
        assertTrue(transform.contains("AUTOFILL READY"));
        assertTrue(transform.contains("NO CHANGES"));
        assertTrue(transform.contains("NEEDS ATTENTION"));
    }

    @Test
    void waiverAndTradeSummariesDoNotInventEvidence() throws Exception {
        String transform = source("scripts/butler-dashboard-bf815-unified-decision-package-transform.ps1");

        assertTrue(transform.contains("Current waiver evidence"));
        assertTrue(transform.contains("Waiver evidence needs refresh"));
        assertTrue(transform.contains("No actionable waiver move"));
        assertTrue(transform.contains("Decision history available"));
        assertTrue(transform.contains("No trade evidence loaded"));
        assertTrue(transform.contains("No trade decision recorded"));
    }

    @Test
    void bf814StagesBf815AndBf815RemainsPresentationOnly() throws Exception {
        String bf814 = source("scripts/butler-dashboard-bf814-priority-decision-record-transform.ps1");
        String transform = source("scripts/butler-dashboard-bf815-unified-decision-package-transform.ps1");

        int write = bf814.indexOf("[System.IO.File]::WriteAllText($DashboardPath");
        int bf815 = bf814.indexOf("butler-dashboard-bf815-unified-decision-package-transform.ps1");
        assertTrue(write >= 0);
        assertTrue(bf815 > write);
        assertTrue(bf814.contains("& $bf815Transform -DashboardPath $DashboardPath"));

        // BF-815 deliberately names forbidden operations inside its fail-closed guard,
        // so verify that guard exists rather than treating the guard text itself as behavior.
        assertTrue(transform.contains("FantasyProsApiClient|Invoke-RestMethod|BUTLER_FANTASYPROS_API_KEY|AutoFillLineupOptimizer|Method = \"POST\""));
        assertTrue(transform.contains("decision package introduced provider, optimizer, credential, or write behavior."));
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
        throw new IOException("BF-815 test could not locate " + relativePath);
    }
}
