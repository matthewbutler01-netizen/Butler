package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf678RefreshConfirmationTest {

    @Test
    void confirmationPresentsDecisionControlsBeforeExtendedGovernance() throws Exception {
        String refresh = script("scripts/butler-decision-refresh.ps1");
        String confirmation = confirmationSection(refresh);

        int title = confirmation.indexOf("Refresh Butler data?");
        int noSleeper = confirmation.indexOf("This does not submit a lineup, waiver move, trade, or FAAB change to Sleeper.");
        int form = confirmation.indexOf("<form method=\"post\" action=\"/refresh\">");
        int confirm = confirmation.indexOf("Confirm refresh");
        int cancel = confirmation.indexOf("class=\"refresh-cancel\" href=\"/\">Cancel</a>");
        int details = confirmation.indexOf("<details class=\"refresh-governance\">");

        assertTrue(title >= 0);
        assertTrue(noSleeper > title);
        assertTrue(form > noSleeper);
        assertTrue(confirm > form);
        assertTrue(cancel > confirm);
        assertTrue(details > cancel);
    }

    @Test
    void extendedGovernanceRemainsAvailableThroughNativeDetails() throws Exception {
        String confirmation = confirmationSection(script("scripts/butler-decision-refresh.ps1"));

        assertTrue(confirmation.contains("<details class=\"refresh-governance\"><summary>How Butler governs this refresh</summary>"));
        assertTrue(confirmation.contains("manual recheck under BF-675"));
        assertTrue(confirmation.contains("BF-823 first performs a read-only roster/player recovery probe."));
        assertTrue(confirmation.contains("If current player mappings or exact roster evidence need repair, only the governed Butler-local recovery chain is allowed."));
        assertTrue(confirmation.contains("If lineup recovery is not needed, the unchanged BF-676 waiver refresh runner performs its existing strict preflight before any Butler evidence write."));
        assertTrue(confirmation.contains("If any required state is ambiguous or unsafe, the refresh stops instead of guessing."));
    }

    @Test
    void confirmationPostsOnlyTheExistingOneUseTokenToExactRefreshRoute() throws Exception {
        String confirmation = confirmationSection(script("scripts/butler-decision-refresh.ps1"));

        assertTrue(confirmation.contains("<form method=\"post\" action=\"/refresh\">"));
        assertTrue(confirmation.contains("<input type=\"hidden\" name=\"token\" value=\"$safeToken\">"));
        assertEquals(1, count(confirmation, "name=\"token\""));
        assertFalse(confirmation.contains("name=\"league"));
        assertFalse(confirmation.contains("name=\"state"));
        assertFalse(confirmation.contains("<script"));
    }

    @Test
    void bf676WriteBoundaryAndRunnerRemainUnchanged() throws Exception {
        String refresh = script("scripts/butler-decision-refresh.ps1");
        String shell = script("scripts/butler-app-shell.ps1");
        String worker = script("scripts/butler-app-request-worker.ps1");

        assertTrue(refresh.contains("BF-676 BLOCKED: refresh POST must contain only the one-use token."));
        assertTrue(refresh.contains("if ($values.Count -ne 1 -or -not $values.ContainsKey('token'))"));
        assertTrue(shell.contains("[hashtable]::Synchronized(@{ Token = $decisionRefreshToken })"));
        assertTrue(worker.contains("$SubmittedToken -cne [string]$State.Token"));
        assertTrue(worker.contains("$State.Token = New-DecisionRefreshToken"));
        assertTrue(worker.contains("Invoke-DecisionRefreshRunner -LeagueId $LeagueId -RunnerPath $DecisionRefreshRunner"));
    }

    @Test
    void bf678FilesRemainAsciiOnly() throws Exception {
        assertAscii(script("scripts/butler-decision-refresh.ps1"));
        assertAscii(script("scripts/butler-app-shell.ps1"));
        assertAscii(script("scripts/butler-app-request-worker.ps1"));
    }

    private static String confirmationSection(String refresh) {
        int start = refresh.indexOf("function Get-DecisionRefreshConfirmationHtml");
        int end = refresh.indexOf("function Read-DecisionRefreshFormBody");
        assertTrue(start >= 0 && end > start, "BF-678 confirmation section is missing");
        return refresh.substring(start, end);
    }

    private static int count(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
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
        throw new IOException("BF-678 test could not locate " + relativePath);
    }
}
