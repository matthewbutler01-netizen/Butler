package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPriorityDecisionRecordBf814Test {

    @Test
    void lineupDecisionRecordFollowsSavedAutoFillState() throws Exception {
        String transform = source("scripts/butler-dashboard-bf814-priority-decision-record-transform.ps1");

        assertTrue(transform.contains("Lineup review saved locally"));
        assertTrue(transform.contains("projection coverage is incomplete"));
        assertTrue(transform.contains("No lineup was submitted"));
        assertTrue(transform.contains("Saved lineup frame needs refresh"));
        assertTrue(transform.contains("Lineup recommendation saved locally"));
        assertTrue(transform.contains("No-change lineup result saved locally"));
        assertTrue(transform.contains("No lineup review recorded yet"));
        assertTrue(transform.contains("\"EVIDENCE GAP\""));
        assertTrue(transform.contains("\"REFRESH AUTOFILL\""));
        assertTrue(transform.contains("\"AUTOFILL READY\""));
        assertTrue(transform.contains("\"NO CHANGES\""));
    }

    @Test
    void waiverAndTradeRecordsRemainTruthful() throws Exception {
        String transform = source("scripts/butler-dashboard-bf814-priority-decision-record-transform.ps1");

        assertTrue(transform.contains("Waiver decision saved and traceable"));
        assertTrue(transform.contains("View Decision History"));
        assertTrue(transform.contains("No trade decision recorded"));
        assertTrue(transform.contains("Open Trade Lab"));
        assertTrue(transform.contains("No specific trade has been evaluated"));
    }

    @Test
    void bf813ChainsBf814AfterEvidenceAndBothRemainPresentationOnly() throws Exception {
        String bf813 = source("scripts/butler-dashboard-bf813-priority-evidence-transform.ps1");
        String bf814 = source("scripts/butler-dashboard-bf814-priority-decision-record-transform.ps1");

        assertTrue(bf813.contains("butler-dashboard-bf814-priority-decision-record-transform.ps1"));
        assertTrue(bf813.contains("& $bf814Transform -DashboardPath $DashboardPath"));
        assertTrue(bf814.contains("$primaryRecordHtml"));

        assertFalse(bf814.contains("Invoke-RestMethod"));
        assertFalse(bf814.contains("BUTLER_FANTASYPROS_API_KEY"));
        assertFalse(bf814.contains("AutoFillLineupOptimizer"));
        assertFalse(bf814.contains("Method = \"POST\""));
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
        throw new IOException("BF-814 test could not locate " + relativePath);
    }
}
