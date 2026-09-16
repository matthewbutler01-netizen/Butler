package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPriorityEvidenceBf813Test {

    @Test
    void lineupPriorityUsesSavedAutoFillTrustFrame() throws Exception {
        String transform = source("scripts/butler-dashboard-bf813-priority-evidence-transform.ps1");

        assertTrue(transform.contains("<strong>AutoFill snapshot</strong>"));
        assertTrue(transform.contains("<strong>Weekly frame</strong>"));
        assertTrue(transform.contains("<strong>Projection coverage</strong>"));
        assertTrue(transform.contains("$snapshotValue = if ($snapshotFresh -and $snapshotTargetMatches -and $verification.RosterOk)"));
        assertTrue(transform.contains("$frameValue = \"$weekText | $scoringText\""));
        assertTrue(transform.contains("\"EVIDENCE GAP\""));
        assertTrue(transform.contains("$coverageValue = \"Incomplete\""));
        assertTrue(transform.contains("$coverageNote = [string]$lineupSignalCopy"));
    }

    @Test
    void evidenceRenderingDoesNotDependOnLaterManagerStatusAssignments() throws Exception {
        String transform = source("scripts/butler-dashboard-bf813-priority-evidence-transform.ps1");

        assertTrue(transform.contains("$evidenceRosterStatusText = if ($verification.RosterOk)"));
        assertTrue(transform.contains("$evidenceLineageStatusText = if ($verification.LineageOk)"));
        assertTrue(transform.contains("$(ConvertTo-HtmlText $evidenceRosterStatusText)"));
        assertTrue(transform.contains("$(ConvertTo-HtmlText $evidenceLineageStatusText)"));
        assertFalse(transform.contains("$(ConvertTo-HtmlText $rosterStatusText)"));
        assertFalse(transform.contains("$(ConvertTo-HtmlText $lineageStatusText)"));
    }

    @Test
    void waiverPriorityPreservesExistingGovernedEvidenceCards() throws Exception {
        String transform = source("scripts/butler-dashboard-bf813-priority-evidence-transform.ps1");

        assertTrue(transform.contains("<strong>Roster check</strong>"));
        assertTrue(transform.contains("<strong>Recommendation data</strong>"));
        assertTrue(transform.contains("<strong>Waiver market</strong>"));
        assertTrue(transform.contains("<strong>Roster / waiver</strong>"));
        assertTrue(transform.contains("$(ConvertTo-HtmlText $market.Human)"));
        assertTrue(transform.contains("$(ConvertTo-HtmlText $waiver.Human)"));
    }

    @Test
    void tradePriorityDoesNotInventEvidence() throws Exception {
        String transform = source("scripts/butler-dashboard-bf813-priority-evidence-transform.ps1");

        assertTrue(transform.contains("<strong>Trade review</strong>"));
        assertTrue(transform.contains("On demand"));
        assertTrue(transform.contains("No specific trade is being evaluated from the Dashboard."));
        assertTrue(transform.contains("Specific deal required"));
        assertTrue(transform.contains("Butler will not invent a trade trust frame without an evaluated proposal."));
    }

    @Test
    void stagingRunsBf813AfterPriorityExplanationAndRemainsPresentationOnly() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");
        String transform = source("scripts/butler-dashboard-bf813-priority-evidence-transform.ps1");

        int bf810 = staging.indexOf("butler-dashboard-bf810-priority-explanation-transform.ps1");
        int bf813 = staging.indexOf("butler-dashboard-bf813-priority-evidence-transform.ps1");
        assertTrue(bf810 >= 0);
        assertTrue(bf813 > bf810);
        assertTrue(staging.contains("& $bf813Transform -DashboardPath $DashboardPath"));

        assertTrue(transform.contains("$primaryEvidenceHtml"));
        assertFalse(transform.contains("$env:"));
        assertFalse(transform.contains("AutoFillLineupOptimizer"));
        assertFalse(transform.contains("Method = \"POST\""));
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
        throw new IOException("BF-813 test could not locate " + relativePath);
    }
}
