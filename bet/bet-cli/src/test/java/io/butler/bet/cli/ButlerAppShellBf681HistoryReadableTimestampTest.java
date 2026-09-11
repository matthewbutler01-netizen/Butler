package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf681HistoryReadableTimestampTest {

    @Test
    void historyFormatsCaptureTimeAsHumanReadableUtcOnly() throws Exception {
        String history = script("scripts/butler-decision-history.ps1");
        String formatter = section(history, "function ConvertTo-HistoryCapturedLabel", "function ConvertTo-DecisionHistoryHtml");

        assertTrue(formatter.contains("[System.DateTimeOffset]::Parse("));
        assertTrue(formatter.contains("$parsed.ToUniversalTime().ToString(\"MMM d, yyyy HH:mm 'UTC'\""));
        assertTrue(formatter.contains("[System.Globalization.CultureInfo]::InvariantCulture"));
        assertFalse(formatter.contains("ToLocalTime"));
        assertFalse(formatter.contains("TimeZoneInfo"));
    }

    @Test
    void unparseableCaptureTimeFallsBackToExactSourceValue() throws Exception {
        String history = script("scripts/butler-decision-history.ps1");
        String formatter = section(history, "function ConvertTo-HistoryCapturedLabel", "function ConvertTo-DecisionHistoryHtml");

        assertTrue(formatter.contains("catch {"));
        assertTrue(formatter.contains("return $Captured"));
    }

    @Test
    void parsedModelKeepsExactCapturedValueAndHtmlUsesPresentationLabel() throws Exception {
        String history = script("scripts/butler-decision-history.ps1");
        String view = section(history, "function ConvertTo-DecisionHistoryView", "function ConvertTo-HistoryCapturedLabel");
        String html = section(history, "function ConvertTo-DecisionHistoryHtml", "function Invoke-DecisionHistoryHtml");

        assertTrue(view.contains("Captured = $audit.Groups['captured'].Value.Trim()"));
        assertTrue(html.contains("$capturedLabel = ConvertTo-HistoryCapturedLabel -Captured $entry.Captured"));
        assertTrue(html.contains("<h3>$(ConvertTo-HtmlText $capturedLabel)</h3>"));
        assertFalse(html.contains("<h3>$(ConvertTo-HtmlText $entry.Captured)</h3>"));
        assertTrue(count(html, "Captured UTC: $(ConvertTo-HtmlText $entry.Captured)") >= 2);
    }

    @Test
    void bf681PreservesNewestFirstCompactReadOnlyAndExactDetailContract() throws Exception {
        String history = script("scripts/butler-decision-history.ps1");
        String html = section(history, "function ConvertTo-DecisionHistoryHtml", "function Invoke-DecisionHistoryHtml");
        String detail = script("scripts/butler-decision-detail.ps1");

        assertTrue(html.contains("for ($entryIndex = $presentationEntries.Count - 1; $entryIndex -ge 0; $entryIndex--)"));
        assertTrue(html.contains("$isNewest = $entryIndex -eq ($presentationEntries.Count - 1)"));
        assertTrue(html.contains("<article class=\"history-card history-card-compact\">"));
        assertTrue(html.contains("<details class=\"history-older-details\"><summary>Show audit details</summary>"));
        assertTrue(detail.contains("$marker = \"<p class=`\"history-lineage`\">Audit: $safeAudit</p>\""));
        assertTrue(detail.contains("/history?audit=$encodedAudit"));
        assertFalse(history.contains("sleeperLiveWaiverRecommendationAuditCapture"));
        assertFalse(history.contains("sleeperLiveWaiverSnapshotSync"));
        assertFalse(history.contains("sleeperLiveWaiverMarketAttentionSync"));
        assertFalse(history.contains("<script"));
        assertAscii(history);
    }

    private static int count(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = text.indexOf(needle, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + needle.length();
        }
    }

    private static String section(String text, String startNeedle, String endNeedle) {
        int start = text.indexOf(startNeedle);
        int end = text.indexOf(endNeedle);
        assertTrue(start >= 0 && end > start, "BF-681 source section is missing");
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
        throw new IOException("BF-681 test could not locate " + relativePath);
    }
}
