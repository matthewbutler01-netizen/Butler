package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerRefreshManagerUxBf824Test {

    @Test
    void confirmationLeadsWithManagerCopyAndKeepsGovernanceBehindTechnicalDetails() throws Exception {
        String refresh = source("scripts/butler-decision-refresh.ps1");
        String confirmation = section(refresh,
            "function Get-DecisionRefreshConfirmationHtml",
            "function Read-DecisionRefreshFormBody");

        int title = confirmation.indexOf("Refresh Butler's data?");
        int plainLanguage = confirmation.indexOf("some of its local fantasy data may be outdated");
        int status = confirmation.indexOf("CONFIRMATION REQUIRED");
        int noSleeper = confirmation.indexOf("Nothing will be submitted to Sleeper.");
        int form = confirmation.indexOf("<form method=\"post\" action=\"/refresh\">");
        int details = confirmation.indexOf("<summary>Technical details</summary>");
        int bf823 = confirmation.indexOf("BF-823 first performs a read-only roster/player recovery probe.");

        assertTrue(title >= 0);
        assertTrue(plainLanguage > title);
        assertTrue(status > title);
        assertTrue(noSleeper > title);
        assertTrue(form > noSleeper);
        assertTrue(details > form);
        assertTrue(bf823 > details, "BF implementation detail must remain behind Technical details");
        assertFalse(confirmation.contains("REVIEW FIRST"));
    }

    @Test
    void completionReturnsManagerToProductSurfacesInsteadOfRetryingTechnicalFlow() throws Exception {
        String refresh = source("scripts/butler-decision-refresh.ps1");
        String success = section(refresh,
            "function Get-DecisionRefreshSuccessHtml",
            "function Get-DecisionRefreshFailureHtml");

        int title = success.indexOf("Butler is up to date");
        int status = success.indexOf("UP TO DATE");
        int noSleeper = success.indexOf("No changes were submitted to Sleeper.");
        int dashboard = success.indexOf("href=\"/\">Return to Dashboard</a>");
        int team = success.indexOf("href=\"/team\">Review My Team</a>");
        int history = success.indexOf("href=\"/history\">View History</a>");
        int details = success.indexOf("<summary>Technical details</summary>");
        int result = success.indexOf("$safeResult", details);

        assertTrue(title >= 0);
        assertTrue(status > title);
        assertTrue(noSleeper > title);
        assertTrue(dashboard > noSleeper);
        assertTrue(team > dashboard);
        assertTrue(history > team);
        assertTrue(details > history);
        assertTrue(result > details, "raw BF result must remain behind Technical details");
        assertFalse(success.contains("Retry Lineup Review"));
        assertFalse(success.contains("Butler data refresh complete"));
        assertFalse(success.contains("Open immutable History"));
    }

    @Test
    void bf824DoesNotChangeExplicitRefreshWriteBoundary() throws Exception {
        String refresh = source("scripts/butler-decision-refresh.ps1");

        assertTrue(refresh.contains("<form method=\"post\" action=\"/refresh\">"));
        assertTrue(refresh.contains("<input type=\"hidden\" name=\"token\" value=\"$safeToken\">"));
        assertTrue(refresh.contains("if ($values.Count -ne 1 -or -not $values.ContainsKey('token'))"));
        assertTrue(refresh.contains("BF-676 BLOCKED: refresh POST must contain only the one-use token."));
        assertTrue(refresh.contains("$recoveryRunner -LeagueId $LeagueId -ProbeOnly"));
        assertTrue(refresh.contains("& $RunnerPath -LeagueId $LeagueId"));
        assertFalse(refresh.contains("create_transaction"));
        assertFalse(refresh.contains("submitTransaction"));
    }

    @Test
    void bf824RefreshSourceRemainsAsciiOnly() throws Exception {
        String refresh = source("scripts/butler-decision-refresh.ps1");
        assertEquals(refresh, new String(refresh.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII));
    }

    private static String section(String text, String startNeedle, String endNeedle) {
        int start = text.indexOf(startNeedle);
        int end = text.indexOf(endNeedle, start + startNeedle.length());
        assertTrue(start >= 0 && end > start, "BF-824 expected refresh section is missing");
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
        throw new IOException("BF-824 test could not locate " + relativePath);
    }
}
