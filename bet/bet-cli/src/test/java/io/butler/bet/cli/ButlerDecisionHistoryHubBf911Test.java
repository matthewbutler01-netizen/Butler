package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDecisionHistoryHubBf911Test {

    @Test
    void newestDecisionUsesManagerFacingOutcomeWithoutRawPlayerIds() throws Exception {
        String history = source("scripts/butler-decision-history.ps1");

        assertTrue(history.contains("'No move recorded'"));
        assertTrue(history.contains("'Waiver move recorded'"));
        assertTrue(history.contains("'Waiver decision recorded'"));
        assertFalse(history.contains("\"ADD $(ConvertTo-HtmlText $entry.AddSleeperId) / DROP $(ConvertTo-HtmlText $entry.DropSleeperId)\""));

        int newest = history.indexOf("<div class=\"eyebrow\">Latest recorded waiver decision</div>");
        int details = history.indexOf("<details><summary>Decision details</summary>", newest);
        assertTrue(newest >= 0 && details > newest);

        String firstScan = history.substring(newest, details);
        assertTrue(firstScan.contains("$decisionLabel"));
        assertTrue(firstScan.contains("$decisionSummary"));
        assertTrue(firstScan.contains("$decisionStatus"));
        assertFalse(firstScan.contains("$entry.AddSleeperId"));
        assertFalse(firstScan.contains("$entry.DropSleeperId"));
        assertFalse(firstScan.contains("Recommendation state"));
        assertFalse(firstScan.contains("Audit:"));
    }

    @Test
    void historyHubLeadsWithUsefulSummaryInsteadOfRawRosterIdentifiers() throws Exception {
        String history = source("scripts/butler-decision-history.ps1");

        assertTrue(history.contains("Your waiver decision timeline"));
        assertTrue(history.contains("Latest outcome"));
        assertTrue(history.contains("Latest recorded"));
        assertTrue(history.contains("Newest first"));
        assertTrue(history.contains("Decision History &middot; $(ConvertTo-HtmlText $History.RecordCount) recorded"));
        assertFalse(history.contains("<div class=\"target\">$(ConvertTo-HtmlText $History.LeagueId) &middot; roster $(ConvertTo-HtmlText $History.RosterId)</div>"));
    }

    @Test
    void rawIdentifiersAndExactAuditMarkersRemainBehindDetails() throws Exception {
        String history = source("scripts/butler-decision-history.ps1");

        for (String marker : new String[]{
                "Recommendation state",
                "Captured UTC: $(ConvertTo-HtmlText $entry.Captured)",
                "Audit: $(ConvertTo-HtmlText $entry.AuditId)",
                "BF-603 market: $(ConvertTo-HtmlText $entry.MarketSnapshotId)",
                "BF-602 waiver: $(ConvertTo-HtmlText $entry.WaiverSnapshotId)",
                "ADD / DROP Sleeper ids: $(ConvertTo-HtmlText $entry.AddSleeperId) / $(ConvertTo-HtmlText $entry.DropSleeperId)"
        }) {
            assertTrue(history.contains(marker), "missing preserved detail marker " + marker);
        }
    }

    @Test
    void olderTimelineRemainsCompactNewestFirstAndReadOnly() throws Exception {
        String history = source("scripts/butler-decision-history.ps1");

        assertTrue(history.contains("$presentationEntries = @($History.Entries)"));
        assertTrue(history.contains("for ($entryIndex = $presentationEntries.Count - 1; $entryIndex -ge 0; $entryIndex--)"));
        assertTrue(history.contains("<article class=\"history-card history-card-compact\">"));
        assertTrue(history.contains("<summary>Show decision details</summary>"));

        assertTrue(history.contains("-Task ':bet:bet-cli:sleeperLiveWaiverRecommendationAuditHistory'"));
        assertFalse(history.contains("Method = \"POST\""));
        assertFalse(history.contains("sleeperLiveWaiverRecommendationAuditCapture"));
        assertFalse(history.contains("sleeperLiveWaiverSnapshotSync"));
        assertFalse(history.contains("sleeperLiveWaiverMarketAttentionSync"));
    }

    @Test
    void historyModuleRemainsAsciiOnly() throws Exception {
        String history = source("scripts/butler-decision-history.ps1");
        byte[] encoded = history.getBytes(StandardCharsets.US_ASCII);
        assertEquals(history, new String(encoded, StandardCharsets.US_ASCII));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-911 test could not locate " + relativePath);
    }
}
