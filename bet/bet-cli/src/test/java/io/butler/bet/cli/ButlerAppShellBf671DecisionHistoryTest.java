package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf671DecisionHistoryTest {

    @Test
    void publicShellOwnsNativeHistoryRouteWithImmediateFirstPaint() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");
        String worker = script("scripts/butler-app-request-worker.ps1");
        String history = script("scripts/butler-decision-history.ps1");

        assertTrue(shell.contains("butler-decision-history.ps1"));
        assertTrue(shell.contains(". $history"));
        assertTrue(worker.contains("$path -eq '/history'"));
        assertTrue(worker.contains("$requestTarget -ceq '/history'"));
        assertTrue(worker.contains("Get-DecisionHistoryLoadingHtml -LeagueId $LeagueId"));
        assertTrue(worker.contains("Invoke-DecisionHistoryHtml -LeagueId $LeagueId"));
        assertTrue(worker.contains("\"history\":\"ready\""));
        assertTrue(history.contains("http-equiv=\"refresh\" content=\"1;url=/history?load=1\""));
        assertTrue(history.contains("Opening Decision History..."));

        int immediatePaint = worker.indexOf("Get-DecisionHistoryLoadingHtml -LeagueId $LeagueId");
        int governedLoad = worker.indexOf("Invoke-DecisionHistoryHtml -LeagueId $LeagueId");
        assertTrue(immediatePaint >= 0 && governedLoad > immediatePaint);
    }

    @Test
    void historyUsesOnlyAuthoritativeBf628ReadOnlyProjection() throws Exception {
        String history = script("scripts/butler-decision-history.ps1");

        assertTrue(history.contains("-Task ':bet:bet-cli:sleeperLiveWaiverRecommendationAuditHistory'"));
        assertTrue(history.contains("BF-628 history league does not match the configured Butler league"));
        assertTrue(history.contains("Policy:"));
        assertTrue(history.contains("Butler league / BF-623 verified owner:"));
        assertTrue(history.contains("Sleeper league / target roster:"));
        assertTrue(history.contains("History state:"));
        assertTrue(history.contains("Immutable audit records:"));
        assertTrue(history.contains("BF-603 market / BF-602 waiver="));
        assertTrue(history.contains("selection / recommendation="));
        assertTrue(history.contains("add / drop Sleeper ids="));
        assertTrue(history.contains("integrity="));
        assertTrue(history.contains("parsed history count does not match BF-628 immutable audit record count"));
    }

    @Test
    void historyNavigationCoexistsWithExistingAppPages() throws Exception {
        String history = script("scripts/butler-decision-history.ps1");
        String worker = script("scripts/butler-app-request-worker.ps1");

        assertTrue(history.contains("href=`\"/`\">Dashboard"));
        assertTrue(history.contains("href=`\"/team`\">My Team"));
        assertTrue(history.contains("href=`\"/waivers`\">Waiver Board"));
        assertTrue(history.contains("href=`\"/league`\">League"));
        assertTrue(history.contains("href=`\"/trade`\">Trade Lab"));
        assertTrue(history.contains("href=`\"/history`\">History"));
        assertTrue(history.contains("function Add-AppNavigation"));
        assertTrue(worker.contains("$body = Add-AppNavigation -Html $body"));
    }

    @Test
    void historyPagePreservesReadOnlyBoundaryAndEmptyState() throws Exception {
        String history = script("scripts/butler-decision-history.ps1");
        String worker = script("scripts/butler-app-request-worker.ps1");

        assertTrue(history.contains("BF-628 reports no immutable governed waiver audits"));
        assertTrue(history.contains("BF-671 displays BF-628 history only"));
        assertFalse(history.contains("sleeperLiveWaiverRecommendationAuditCapture"));
        assertFalse(history.contains("sleeperLiveWaiverGovernedExplanationCapture"));
        assertFalse(history.contains("sleeperLiveWaiverSnapshotSync"));
        assertFalse(history.contains("sleeperLiveWaiverMarketAttentionSync"));
        assertFalse(history.contains("trade recommendation"));
        assertFalse(history.contains("Invoke-Expression"));
        assertFalse(history.contains("Start-Job"));
        assertFalse(history.contains("Stop-Process"));
        assertTrue(worker.contains("$parts[0] -ne 'GET'"));
        assertTrue(worker.contains("form-action 'self'"));
    }

    @Test
    void bf671FilesRemainAsciiOnly() throws Exception {
        assertAscii(script("scripts/butler-app-shell.ps1"));
        assertAscii(script("scripts/butler-app-request-worker.ps1"));
        assertAscii(script("scripts/butler-decision-history.ps1"));
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
        throw new IOException("BF-671 test could not locate " + relativePath);
    }
}
