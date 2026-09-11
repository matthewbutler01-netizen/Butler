package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf680HistoryProgressiveDisclosureTest {

    @Test
    void newestAuditRemainsFullWhileOlderAuditsUseNativeDisclosure() throws Exception {
        String history = script("scripts/butler-decision-history.ps1");
        String html = section(history, "function ConvertTo-DecisionHistoryHtml", "function Invoke-DecisionHistoryHtml");

        assertTrue(html.contains("$isNewest = $entryIndex -eq ($presentationEntries.Count - 1)"));
        assertTrue(html.contains("if ($isNewest)"));
        assertTrue(html.contains("<article class=\"history-card\">"));
        assertTrue(html.contains("<article class=\"history-card history-card-compact\">"));
        assertTrue(html.contains("<details class=\"history-older-details\"><summary>Show audit details</summary>"));
        assertTrue(html.contains("<details><summary>Audit and evidence lineage</summary>"));
        assertFalse(html.contains("<script"));
    }

    @Test
    void olderDisclosureKeepsEveryGovernedFieldAndExactAuditMarker() throws Exception {
        String history = script("scripts/butler-decision-history.ps1");
        String html = section(history, "function ConvertTo-DecisionHistoryHtml", "function Invoke-DecisionHistoryHtml");
        String detail = script("scripts/butler-decision-detail.ps1");

        assertTrue(html.contains("ConvertTo-HtmlText $entry.ProviderSeason"));
        assertTrue(html.contains("ConvertTo-HtmlText $entry.ProviderStatus"));
        assertTrue(html.contains("ConvertTo-HtmlText $entry.ProviderLeg"));
        assertTrue(html.contains("ConvertTo-HtmlText $entry.SelectionState"));
        assertTrue(html.contains("ConvertTo-HtmlText $entry.RecommendationState"));
        assertTrue(count(html, "Audit: $(ConvertTo-HtmlText $entry.AuditId)") >= 2);
        assertTrue(count(html, "BF-603 market: $(ConvertTo-HtmlText $entry.MarketSnapshotId)") >= 2);
        assertTrue(count(html, "BF-602 waiver: $(ConvertTo-HtmlText $entry.WaiverSnapshotId)") >= 2);
        assertTrue(count(html, "ADD / DROP Sleeper ids: $(ConvertTo-HtmlText $entry.AddSleeperId) / $(ConvertTo-HtmlText $entry.DropSleeperId)") >= 2);
        assertTrue(detail.contains("$marker = \"<p class="));
        assertTrue(detail.contains("history-lineage"));
        assertTrue(detail.contains("Audit: $safeAudit</p>"));
        assertTrue(detail.contains("/history?audit=$encodedAudit"));
        assertTrue(detail.contains("View decision"));
    }

    @Test
    void bf680PreservesNewestFirstModelAndReadOnlyBoundary() throws Exception {
        String history = script("scripts/butler-decision-history.ps1");
        String view = section(history, "function ConvertTo-DecisionHistoryView", "function ConvertTo-DecisionHistoryHtml");
        String html = section(history, "function ConvertTo-DecisionHistoryHtml", "function Invoke-DecisionHistoryHtml");

        assertTrue(view.contains("Entries = @($entries)"));
        assertFalse(view.contains("Sort-Object"));
        assertTrue(html.contains("$presentationEntries = @($History.Entries)"));
        assertTrue(html.contains("for ($entryIndex = $presentationEntries.Count - 1; $entryIndex -ge 0; $entryIndex--)"));
        assertFalse(html.contains("Sort-Object"));
        assertFalse(history.contains("sleeperLiveWaiverRecommendationAuditCapture"));
        assertFalse(history.contains("sleeperLiveWaiverSnapshotSync"));
        assertFalse(history.contains("sleeperLiveWaiverMarketAttentionSync"));
        assertTrue(history.contains("BF-628 reports no immutable governed waiver audits"));
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
        assertTrue(start >= 0 && end > start, "BF-680 source section is missing");
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
        throw new IOException("BF-680 test could not locate " + relativePath);
    }
}
