package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDecisionHistoryScopeBf998Test {

    @Test
    void historyExplicitlyStatesItsCurrentWaiverOnlyCoverage() throws Exception {
        String history = source("scripts/butler-decision-history.ps1");

        assertTrue(history.contains("<span class=\"eyebrow\">Current coverage</span>"));
        assertTrue(history.contains("<strong>Waiver decisions only</strong>"));
        assertTrue(history.contains("This timeline does not include lineup reviews or trade analyses."));
        assertTrue(history.contains("Use their dedicated workflows for current decision support."));
        assertTrue(history.contains("Your waiver decision timeline"));
    }

    @Test
    void historyKeepsNewestFirstManagerSummaryAndExistingActions() throws Exception {
        String history = source("scripts/butler-decision-history.ps1");

        assertTrue(history.contains("for ($entryIndex = $presentationEntries.Count - 1; $entryIndex -ge 0; $entryIndex--)"));
        assertTrue(history.contains("Latest recorded waiver decision"));
        assertTrue(history.contains("href=\"/waivers\">Review Waiver Board</a>"));
        assertTrue(history.contains("href=\"/\">Back to Dashboard</a>"));
        assertTrue(history.contains("View older decisions ($olderCount)"));
    }

    @Test
    void scopeClarificationDoesNotBroadenHistoryReadOrAddWrites() throws Exception {
        String history = source("scripts/butler-decision-history.ps1");

        assertTrue(history.contains("-Task ':bet:bet-cli:sleeperLiveWaiverRecommendationAuditHistory'"));
        assertTrue(history.contains("Decision History reads recorded governed waiver history only."));

        for (String forbidden : new String[] {
            "sleeperLiveWaiverRecommendationAuditCapture",
            "sleeperLiveWaiverSnapshotSync",
            "sleeperLiveWaiverMarketAttentionSync",
            "Method = \"POST\"",
            "submitTransaction",
            "setFaab",
            "https://api.sleeper.app"
        }) {
            assertFalse(history.contains(forbidden),
                "BF-998 Decision History introduced forbidden read/write behavior " + forbidden);
        }
    }

    @Test
    void historyScopePresentationIsResponsiveAndAsciiOnly() throws Exception {
        String history = source("scripts/butler-decision-history.ps1");

        assertTrue(history.contains(".history-scope{"));
        assertTrue(history.contains(".history-scope{display:grid;grid-template-columns:1fr}"));
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(history));
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
        throw new IOException("BF-998 test could not locate " + relativePath);
    }
}
