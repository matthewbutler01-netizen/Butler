package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf684ExplanationReadableTimestampTest {

    @Test
    void explanationCaptureReusesHistoryFormatterWithoutNewTimestampPolicy() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");
        String detail = script("scripts/butler-decision-detail.ps1");
        String detailHtml = section(detail, "function ConvertTo-DecisionDetailHtml", "function Invoke-DecisionHistoryHtml");

        assertTrue(shell.indexOf(". $history") >= 0);
        assertTrue(shell.indexOf(". $detail") > shell.indexOf(". $history"));
        assertTrue(detailHtml.contains("ConvertTo-HistoryCapturedLabel -Captured $Explanation.Captured"));
        assertFalse(detailHtml.contains("[System.DateTimeOffset]::Parse("));
        assertFalse(detailHtml.contains("ToLocalTime"));
        assertFalse(detailHtml.contains("TimeZoneInfo"));
    }

    @Test
    void readyExplanationUsesReadableCaptureAndRetainsExactPersistedTimestamp() throws Exception {
        String detail = script("scripts/butler-decision-detail.ps1");
        String detailHtml = section(detail, "function ConvertTo-DecisionDetailHtml", "function Invoke-DecisionHistoryHtml");

        assertTrue(detailHtml.contains("$explanationCapturedLabel = if ($Explanation.State -ceq 'EXPLANATION_READY')"));
        assertTrue(detailHtml.contains("<strong>Explanation captured</strong><span>$(ConvertTo-HtmlText $explanationCapturedLabel)</span>"));
        assertFalse(detailHtml.contains("<strong>Explanation captured</strong><span>$(ConvertTo-HtmlText $Explanation.Captured)</span>"));
        assertTrue(detailHtml.contains("<summary>Explanation evidence lineage</summary><p class=\"mono\">Captured UTC: $(ConvertTo-HtmlText $Explanation.Captured)</p>"));
    }

    @Test
    void bf653ParsedCaptureAndNotCapturedStateRemainUnchanged() throws Exception {
        String detail = script("scripts/butler-decision-detail.ps1");
        String explanationView = section(detail, "function ConvertTo-DecisionExplanationView", "function Assert-DecisionExplanationReconciled");
        String detailHtml = section(detail, "function ConvertTo-DecisionDetailHtml", "function Invoke-DecisionHistoryHtml");

        assertTrue(explanationView.contains("Captured = $captured.Groups['value'].Value.Trim()"));
        assertTrue(explanationView.contains("EXPLANATION_NOT_CAPTURED"));
        assertTrue(explanationView.contains("if ($value -cne 'none')"));
        assertTrue(detailHtml.contains("No persisted explanation"));
        assertTrue(detailHtml.contains("BF-653 reports EXPLANATION_NOT_CAPTURED"));
    }

    @Test
    void bf684PreservesBf682ImmutableCaptureAndReadOnlyBoundary() throws Exception {
        String detail = script("scripts/butler-decision-detail.ps1");
        String detailHtml = section(detail, "function ConvertTo-DecisionDetailHtml", "function Invoke-DecisionHistoryHtml");

        assertTrue(detailHtml.contains("$capturedLabel = ConvertTo-HistoryCapturedLabel -Captured $Entry.Captured"));
        assertTrue(detailHtml.contains("Captured $(ConvertTo-HtmlText $capturedLabel). This detail view is reconciled"));
        assertTrue(detailHtml.contains("Captured UTC: $(ConvertTo-HtmlText $Entry.Captured)"));
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
        assertTrue(start >= 0 && end > start, "BF-684 source section is missing");
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
        throw new IOException("BF-684 test could not locate " + relativePath);
    }
}
