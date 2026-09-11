package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf679HistoryNewestFirstTest {

    @Test
    void parsedHistoryKeepsAuthoritativeBf628SourceOrder() throws Exception {
        String history = script("scripts/butler-decision-history.ps1");
        String view = section(history, "function ConvertTo-DecisionHistoryView", "function ConvertTo-DecisionHistoryHtml");

        assertTrue(view.contains("$entries += [pscustomobject]@{"));
        assertTrue(view.contains("Entries = @($entries)"));
        assertFalse(view.contains("Sort-Object"));
        assertFalse(view.contains("[array]::Reverse"));
    }

    @Test
    void htmlPresentationWalksAuthoritativeSequenceFromLastToFirst() throws Exception {
        String history = script("scripts/butler-decision-history.ps1");
        String html = section(history, "function ConvertTo-DecisionHistoryHtml", "function Invoke-DecisionHistoryHtml");

        assertTrue(html.contains("$presentationEntries = @($History.Entries)"));
        assertTrue(html.contains("for ($entryIndex = $presentationEntries.Count - 1; $entryIndex -ge 0; $entryIndex--)"));
        assertTrue(html.contains("$entry = $presentationEntries[$entryIndex]"));
        assertFalse(html.contains("foreach ($entry in @($History.Entries))"));
        assertFalse(html.contains("Sort-Object"));
        assertFalse(html.contains("[datetime]"));
        assertFalse(html.contains("ParseExact"));
    }

    @Test
    void newestFirstPresentationPreservesExactAuditCardContract() throws Exception {
        String history = script("scripts/butler-decision-history.ps1");
        String html = section(history, "function ConvertTo-DecisionHistoryHtml", "function Invoke-DecisionHistoryHtml");
        String detail = script("scripts/butler-decision-detail.ps1");

        assertTrue(html.contains("ConvertTo-HtmlText $entry.Captured"));
        assertTrue(html.contains("ConvertTo-HtmlText $entry.IntegrityState"));
        assertTrue(html.contains("ConvertTo-HtmlText $entry.ProviderSeason"));
        assertTrue(html.contains("ConvertTo-HtmlText $entry.ProviderStatus"));
        assertTrue(html.contains("ConvertTo-HtmlText $entry.ProviderLeg"));
        assertTrue(html.contains("ConvertTo-HtmlText $entry.SelectionState"));
        assertTrue(html.contains("ConvertTo-HtmlText $entry.RecommendationState"));
        assertTrue(html.contains("Audit: $(ConvertTo-HtmlText $entry.AuditId)"));
        assertTrue(html.contains("BF-603 market: $(ConvertTo-HtmlText $entry.MarketSnapshotId)"));
        assertTrue(html.contains("BF-602 waiver: $(ConvertTo-HtmlText $entry.WaiverSnapshotId)"));
        assertTrue(html.contains("ADD / DROP Sleeper ids: $(ConvertTo-HtmlText $entry.AddSleeperId) / $(ConvertTo-HtmlText $entry.DropSleeperId)"));
        assertTrue(detail.contains("/history?audit=$encodedAudit"));
        assertTrue(detail.contains("Get-ExactDecisionHistoryEntry -History $history -AuditId $auditId"));
    }

    @Test
    void bf679RemainsReadOnlyEmptySafeAndAsciiOnly() throws Exception {
        String history = script("scripts/butler-decision-history.ps1");

        assertTrue(history.contains("-Task ':bet:bet-cli:sleeperLiveWaiverRecommendationAuditHistory'"));
        assertTrue(history.contains("BF-628 reports no immutable governed waiver audits"));
        assertFalse(history.contains("sleeperLiveWaiverRecommendationAuditCapture"));
        assertFalse(history.contains("sleeperLiveWaiverSnapshotSync"));
        assertFalse(history.contains("sleeperLiveWaiverMarketAttentionSync"));
        assertFalse(history.contains("<script"));
        assertAscii(history);
    }

    private static String section(String text, String startNeedle, String endNeedle) {
        int start = text.indexOf(startNeedle);
        int end = text.indexOf(endNeedle);
        assertTrue(start >= 0 && end > start, "BF-679 source section is missing");
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
        throw new IOException("BF-679 test could not locate " + relativePath);
    }
}
