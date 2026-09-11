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

        int title = confirmation.indexOf("Check for a new decision?");
        int noSleeper = confirmation.indexOf("This does not submit a waiver move to Sleeper.");
        int form = confirmation.indexOf("<form method=\"post\" action=\"/refresh\">");
        int confirm = confirmation.indexOf("Confirm and check again");
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
        assertTrue(confirmation.contains("BF-635 reports the approved six-hour refresh warning"));
        assertTrue(confirmation.contains("BF-636 supplies the exact ready nine-step plan"));
        assertTrue(confirmation.contains("Fully current actionable, stale hard-gate, pending, completed/unconverged, and unknown states are blocked before BF-602."));
        assertTrue(confirmation.contains("The refresh may take several minutes while the browser waits for the nine governed stages."));
        assertTrue(confirmation.contains("If a stage fails, later stages stop; earlier Butler evidence stages may already have completed."));
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

        assertTrue(refresh.contains("BF-676 BLOCKED: refresh POST must contain only the one-use token."));
        assertTrue(refresh.contains("if ($values.Count -ne 1 -or -not $values.ContainsKey('token'))"));
        assertTrue(shell.contains("$submittedToken -cne $decisionRefreshToken"));
        assertTrue(shell.contains("$decisionRefreshToken = New-DecisionRefreshToken"));
        assertTrue(shell.contains("Invoke-DecisionRefreshRunner -LeagueId $LeagueId -RunnerPath $decisionRefreshRunner"));
    }

    @Test
    void bf678FilesRemainAsciiOnly() throws Exception {
        assertAscii(script("scripts/butler-decision-refresh.ps1"));
        assertAscii(script("scripts/butler-app-shell.ps1"));
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
