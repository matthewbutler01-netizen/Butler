package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf685NoTransactionStateLabelsTest {

    @Test
    void humanizesOnlyProvenNoTransactionPrimaryStates() throws Exception {
        String detail = script("scripts/butler-decision-detail.ps1");
        String html = section(detail, "function ConvertTo-DecisionDetailHtml", "function Invoke-DecisionHistoryHtml");

        assertTrue(html.contains("$selectionLabel = if ($Entry.SelectionState -ceq 'NO_HISTORICAL_FINALIST') { 'No unique finalist' } else { $Entry.SelectionState }"));
        assertTrue(html.contains("$recommendationLabel = if ($Entry.RecommendationState -ceq 'NO_GOVERNED_TRANSACTION') { 'No governed transaction' } else { $Entry.RecommendationState }"));
        assertTrue(html.contains("$explanationTypeLabel = if ($Explanation.ExplanationType -ceq 'NO_GOVERNED_TRANSACTION') { 'No governed transaction' } else { $Explanation.ExplanationType }"));
        assertTrue(html.contains("<strong>Selection state</strong><span>$(ConvertTo-HtmlText $selectionLabel)</span>"));
        assertTrue(html.contains("<strong>Recommendation</strong><span>$(ConvertTo-HtmlText $recommendationLabel)</span>"));
        assertTrue(html.contains("<strong>Explanation type</strong><span>$(ConvertTo-HtmlText $explanationTypeLabel)</span>"));
    }

    @Test
    void preservesExactRawStatesInsideLineage() throws Exception {
        String detail = script("scripts/butler-decision-detail.ps1");
        String html = section(detail, "function ConvertTo-DecisionDetailHtml", "function Invoke-DecisionHistoryHtml");

        assertTrue(html.contains("Selection state: $(ConvertTo-HtmlText $Entry.SelectionState)"));
        assertTrue(html.contains("Recommendation state: $(ConvertTo-HtmlText $Entry.RecommendationState)"));
        assertTrue(html.contains("Explanation type: $(ConvertTo-HtmlText $Explanation.ExplanationType)"));
        assertTrue(html.contains("Captured UTC: $(ConvertTo-HtmlText $Entry.Captured)"));
        assertTrue(html.contains("Captured UTC: $(ConvertTo-HtmlText $Explanation.Captured)"));
    }

    @Test
    void parsedGovernedStatesRemainUnchanged() throws Exception {
        String history = script("scripts/butler-decision-history.ps1");
        String detail = script("scripts/butler-decision-detail.ps1");
        String explanation = section(detail, "function ConvertTo-DecisionExplanationView", "function Assert-DecisionExplanationReconciled");

        assertTrue(history.contains("SelectionState = $decision.Groups['selection'].Value.Trim()"));
        assertTrue(history.contains("RecommendationState = $decision.Groups['recommendation'].Value.Trim()"));
        assertTrue(explanation.contains("ExplanationType = $type.Groups['value'].Value.Trim()"));
    }

    @Test
    void preservesReadOnlyAndSuccessorTimestampContracts() throws Exception {
        String detail = script("scripts/butler-decision-detail.ps1");
        String html = section(detail, "function ConvertTo-DecisionDetailHtml", "function Invoke-DecisionHistoryHtml");

        assertTrue(html.contains("$capturedLabel = ConvertTo-HistoryCapturedLabel -Captured $Entry.Captured"));
        assertTrue(html.contains("ConvertTo-HistoryCapturedLabel -Captured $Explanation.Captured"));
        assertTrue(detail.contains("sleeperLiveWaiverRecommendationAuditHistory"));
        assertTrue(detail.contains("sleeperLiveWaiverGovernedExplanationLookup"));
        assertFalse(detail.contains("sleeperLiveWaiverRecommendationAuditCapture"));
        assertFalse(detail.contains("sleeperLiveWaiverSnapshotSync"));
        assertFalse(detail.contains("sleeperLiveWaiverMarketAttentionSync"));
        assertFalse(detail.contains("<script"));
        assertAscii(detail);
    }

    private static String section(String text, String startNeedle, String endNeedle) {
        int start = text.indexOf(startNeedle);
        int end = text.indexOf(endNeedle);
        assertTrue(start >= 0 && end > start, "BF-685 source section is missing");
        return text.substring(start, end);
    }

    private static void assertAscii(String text) {
        byte[] encoded = text.getBytes(StandardCharsets.US_ASCII);
        assertEquals(text, new String(encoded, StandardCharsets.US_ASCII));
    }

    private static String script(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-685 test could not locate " + relativePath);
    }
}
