package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDecisionHistoryLatestFirstBf876Test {

    @Test
    void newestDecisionIsManagerFirstAndAuditProofIsDisclosureOnly() throws Exception {
        String history = source("scripts/butler-decision-history.ps1");

        int newest = history.indexOf("<div class=\"eyebrow\">Latest recorded waiver decision</div>");
        int details = history.indexOf("<details><summary>Decision details</summary>", newest);
        assertTrue(newest >= 0, "latest recorded decision marker must exist");
        assertTrue(details > newest, "decision details must follow the newest decision first scan");

        String firstScan = history.substring(newest, details);
        assertTrue(firstScan.contains("$(ConvertTo-HtmlText $capturedLabel)"));
        assertTrue(firstScan.contains("$decisionLabel"));
        assertFalse(firstScan.contains("Provider frame"));
        assertFalse(firstScan.contains("Selection state"));
        assertFalse(firstScan.contains("Recommendation state"));
        assertFalse(firstScan.contains("Audit:"));
        assertFalse(firstScan.contains("BF-603"));
        assertFalse(firstScan.contains("BF-602"));
        assertFalse(firstScan.contains("Sleeper ids"));
        assertFalse(firstScan.contains("$entry.IntegrityState"));
    }

    @Test
    void exactAuditAndProviderFieldsRemainAvailableBehindDetails() throws Exception {
        String history = source("scripts/butler-decision-history.ps1");

        for (String marker : new String[]{
                "Integrity",
                "Provider frame",
                "Selection state",
                "Recommendation state",
                "Captured UTC: $(ConvertTo-HtmlText $entry.Captured)",
                "Audit: $(ConvertTo-HtmlText $entry.AuditId)",
                "BF-603 market: $(ConvertTo-HtmlText $entry.MarketSnapshotId)",
                "BF-602 waiver: $(ConvertTo-HtmlText $entry.WaiverSnapshotId)",
                "ADD / DROP Sleeper ids: $(ConvertTo-HtmlText $entry.AddSleeperId) / $(ConvertTo-HtmlText $entry.DropSleeperId)"
        }) {
            assertTrue(history.contains(marker), "missing preserved audit field " + marker);
        }
    }

    @Test
    void normalHistoryCopyNoLongerLeadsWithImplementationIdentifiers() throws Exception {
        String history = source("scripts/butler-decision-history.ps1");

        assertTrue(history.contains("Recorded waiver decisions"));
        assertTrue(history.contains("Butler shows the newest recorded decision first for this league and roster."));
        assertTrue(history.contains("No recorded governed waiver decisions are available for this league yet."));
        assertTrue(history.contains("Decision History reads recorded governed waiver history only."));

        assertFalse(history.contains("Loading Butler's BF-628 integrity-verified immutable waiver audit trail."));
        assertFalse(history.contains("<h1 class=\"headline\">Immutable governed waiver audits</h1>"));
        assertFalse(history.contains("BF-628 integrity-verified history for the exact BF-623-bound league and roster."));
        assertFalse(history.contains("<div class=\"eyebrow\">Immutable audit</div>"));
    }

    @Test
    void olderHistoryRemainsCompactAndNewestFirstTraversalIsUntouched() throws Exception {
        String history = source("scripts/butler-decision-history.ps1");

        assertTrue(history.contains("$presentationEntries = @($History.Entries)"));
        assertTrue(history.contains("for ($entryIndex = $presentationEntries.Count - 1; $entryIndex -ge 0; $entryIndex--)"));
        assertTrue(history.contains("$isNewest = $entryIndex -eq ($presentationEntries.Count - 1)"));
        assertTrue(history.contains("<article class=\"history-card history-card-compact\">"));
        assertTrue(history.contains("<summary>Show decision details</summary>"));
        assertFalse(section(history, "function ConvertTo-DecisionHistoryView", "function ConvertTo-HistoryCapturedLabel").contains("Sort-Object"));
    }

    @Test
    void readOnlyAndAuthoritativeHistoryBoundariesRemainIntact() throws Exception {
        String history = source("scripts/butler-decision-history.ps1");

        assertTrue(history.contains("-Task ':bet:bet-cli:sleeperLiveWaiverRecommendationAuditHistory'"));
        assertTrue(history.contains("parsed history count does not match BF-628 immutable audit record count"));
        assertTrue(history.contains("BF-628 history league does not match the configured Butler league"));

        for (String forbidden : new String[]{
                "sleeperLiveWaiverRecommendationAuditCapture",
                "sleeperLiveWaiverSnapshotSync",
                "sleeperLiveWaiverMarketAttentionSync",
                "Invoke-Expression",
                "Method = \"POST\""
        }) {
            assertFalse(history.contains(forbidden), "history introduced write behavior " + forbidden);
        }
    }

    private static String section(String text, String startNeedle, String endNeedle) {
        int start = text.indexOf(startNeedle);
        int end = text.indexOf(endNeedle);
        assertTrue(start >= 0 && end > start, "BF-876 source section is missing");
        return text.substring(start, end);
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
        throw new IOException("BF-876 test could not locate " + relativePath);
    }
}
