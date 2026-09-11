package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf678RefreshConfirmationUxTest {

    @Test
    void confirmationPutsDecisionControlsBeforeExtendedGovernanceDetail() throws Exception {
        String refresh = script("scripts/butler-decision-refresh.ps1");
        String confirmation = confirmationSection(refresh);

        int warning = confirmation.indexOf("This does not submit a waiver move to Sleeper.");
        int form = confirmation.indexOf("<form method=\"post\" action=\"/refresh\">");
        int details = confirmation.indexOf("<details class=\"refresh-details\">");

        assertTrue(warning >= 0, "critical no-Sleeper warning is missing");
        assertTrue(form > warning, "confirm controls must follow the critical warning");
        assertTrue(details > form, "extended governance detail must follow the primary decision controls");
        assertTrue(confirmation.contains("Confirm and check again"));
        assertTrue(confirmation.contains("class=\"refresh-cancel\" href=\"/\">Cancel"));
    }

    @Test
    void governanceDetailUsesNativeProgressiveDisclosureAndPreservesSafetyCopy() throws Exception {
        String confirmation = confirmationSection(script("scripts/butler-decision-refresh.ps1"));

        assertTrue(confirmation.contains("<details class=\"refresh-details\"><summary>How Butler governs this refresh</summary>"));
        assertTrue(confirmation.contains("The current governed decision is re-checked before any Butler write."));
        assertTrue(confirmation.contains("exact governed no-transaction decision remains eligible"));
        assertTrue(confirmation.contains("approved six-hour refresh warning"));
        assertTrue(confirmation.contains("exact ready nine-step plan"));
        assertTrue(confirmation.contains("Fully current actionable, stale hard-gate, pending, completed/unconverged, and unknown states are blocked before BF-602."));
        assertTrue(confirmation.contains("If a stage fails, later stages stop"));
        assertFalse(confirmation.toLowerCase().contains("<script"));
    }

    @Test
    void confirmationFormStillPostsOnlyOneUseTokenToExactRefreshRoute() throws Exception {
        String confirmation = confirmationSection(script("scripts/butler-decision-refresh.ps1"));
        String shell = script("scripts/butler-app-shell.ps1");

        assertTrue(confirmation.contains("<form method=\"post\" action=\"/refresh\">"));
        assertTrue(confirmation.contains("<input type=\"hidden\" name=\"token\" value=\"$safeToken\">"));
        assertFalse(confirmation.contains("name=\"league"));
        assertFalse(confirmation.contains("name=\"task"));
        assertFalse(confirmation.contains("name=\"state"));

        assertTrue(shell.contains("if ($requestTarget -cne '/refresh')"));
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
