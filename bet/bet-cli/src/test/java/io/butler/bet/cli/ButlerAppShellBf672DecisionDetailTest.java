package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf672DecisionDetailTest {

    @Test
    void shellLoadsDetailAfterHistoryAndPassesExactRequestTarget() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");

        assertTrue(shell.contains("butler-decision-detail.ps1"));
        assertTrue(shell.indexOf(". $history") >= 0);
        assertTrue(shell.indexOf(". $detail") > shell.indexOf(". $history"));
        assertTrue(shell.contains("Invoke-DecisionHistoryHtml -LeagueId $LeagueId -RequestTarget $requestTarget"));
        assertTrue(shell.contains("\"decisionDetail\":\"ready\""));
        assertTrue(shell.contains("$parts[0] -ne 'GET'"));
        assertTrue(shell.contains("form-action 'self'"));
    }

    @Test
    void detailValidatesExactBf628AuditBeforeBf653Lookup() throws Exception {
        String detail = script("scripts/butler-decision-detail.ps1");

        assertTrue(detail.contains("Get-ExactDecisionHistoryEntry -History $history -AuditId $auditId"));
        assertTrue(detail.contains("requested BF-627 audit id must resolve exactly once in BF-628 history"));
        assertTrue(detail.contains("-Task ':bet:bet-cli:sleeperLiveWaiverRecommendationAuditHistory'"));
        assertTrue(detail.contains("-Task ':bet:bet-cli:sleeperLiveWaiverGovernedExplanationLookup'"));

        int exactAudit = detail.indexOf("$entry = Get-ExactDecisionHistoryEntry");
        int explanationLookup = detail.indexOf("$explanationRaw = Invoke-ButlerReadOnlyTask");
        assertTrue(exactAudit >= 0 && explanationLookup > exactAudit);
    }

    @Test
    void detailReconcilesPersistedExplanationWithoutGeneratingHistory() throws Exception {
        String detail = script("scripts/butler-decision-detail.ps1");

        assertTrue(detail.contains("EXPLANATION_READY"));
        assertTrue(detail.contains("EXPLANATION_NOT_CAPTURED"));
        assertTrue(detail.contains("BF-653 explanation does not exactly reconcile to the selected BF-628 audit"));
        assertTrue(detail.contains("$Explanation.AuditId -cne $Entry.AuditId"));
        assertTrue(detail.contains("$Explanation.MarketSnapshotId -cne $Entry.MarketSnapshotId"));
        assertTrue(detail.contains("$Explanation.WaiverSnapshotId -cne $Entry.WaiverSnapshotId"));
        assertTrue(detail.contains("$Explanation.AddSleeperId -cne $Entry.AddSleeperId"));
        assertTrue(detail.contains("$Explanation.DropSleeperId -cne $Entry.DropSleeperId"));
        assertTrue(detail.contains("Butler will not reconstruct or generate historical reasoning"));
    }

    @Test
    void historyCardsLinkToImmediateReadOnlyDetailFirstPaint() throws Exception {
        String detail = script("scripts/butler-decision-detail.ps1");

        assertTrue(detail.contains("View decision"));
        assertTrue(detail.contains("/history?audit=$encodedAudit"));
        assertTrue(detail.contains("Opening Decision Detail..."));
        assertTrue(detail.contains("http-equiv=\"refresh\" content=\"1;url=/history?audit=$encodedAudit&amp;detail=1\""));
        assertTrue(detail.contains("Back to Decision History"));
    }

    @Test
    void bf672RemainsReadOnlyAndAsciiOnly() throws Exception {
        String detail = script("scripts/butler-decision-detail.ps1");
        String shell = script("scripts/butler-app-shell.ps1");

        assertFalse(detail.contains("sleeperLiveWaiverRecommendationAuditCapture"));
        assertFalse(detail.contains("sleeperLiveWaiverGovernedExplanationCapture"));
        assertFalse(detail.contains("sleeperLiveWaiverSnapshotSync"));
        assertFalse(detail.contains("sleeperLiveWaiverMarketAttentionSync"));
        assertFalse(detail.contains("trade recommendation"));
        assertFalse(detail.contains("Invoke-Expression"));
        assertFalse(detail.contains("Stop-Process"));
        assertFalse(detail.contains("Start-Job"));
        assertAscii(detail);
        assertAscii(shell);
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
        throw new IOException("BF-672 test could not locate " + relativePath);
    }
}
