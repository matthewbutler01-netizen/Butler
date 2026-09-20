package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWeeklyMatchupDecisionFirstBf881Test {

    @Test
    void matchupHeroLeadsWithExistingLineupDecisionStates() throws Exception {
        String transform = source("scripts/butler-app-bf881-matchup-decision-first-transform.ps1");

        assertTrue(transform.contains("function Get-MatchupLineupDecisionView {"));
        assertTrue(transform.contains("Review this week's lineup"));
        assertTrue(transform.contains("Lineup review needs evidence"));
        assertTrue(transform.contains("Make $($changedAssignments.Count) lineup $changeWord"));
        assertTrue(transform.contains("Keep the current lineup"));
        assertTrue(transform.contains("What to do now"));
    }

    @Test
    void opponentContextRendersAfterLineupAdvisor() throws Exception {
        String transform = source("scripts/butler-app-bf881-matchup-decision-first-transform.ps1");
        String replacement = hereString(transform, "$matchupReplacement = @'", "'@\n\n$core = Replace-FunctionBlock");

        int autoFill = replacement.indexOf("$autoFillHtml");
        int opponent = replacement.indexOf("$opponentHtml");

        assertTrue(autoFill >= 0);
        assertTrue(opponent > autoFill);
        assertTrue(replacement.contains("Opponent context"));
        assertTrue(replacement.contains("does not predict a winner"));
    }

    @Test
    void incompleteOpponentDoesNotHideLineupDecision() throws Exception {
        String transform = source("scripts/butler-app-bf881-matchup-decision-first-transform.ps1");

        assertTrue(transform.contains("Opponent data is incomplete, but your existing Lineup Advisor decision remains the first manager task."));
        assertTrue(transform.contains("Opponent not confirmed"));
        assertTrue(transform.contains("MATCHUP DATA NEEDED"));
        assertTrue(transform.contains("will not guess or imply a matchup result"));
    }

    @Test
    void presentationAddsNoProviderWriteGamblingOrNewModelPath() throws Exception {
        String transform = source("scripts/butler-app-bf881-matchup-decision-first-transform.ps1");
        String replacement = hereString(transform, "$matchupReplacement = @'", "'@\n\n$core = Replace-FunctionBlock");

        assertFalse(replacement.contains("Invoke-RestMethod"));
        assertFalse(replacement.contains("Invoke-WebRequest"));
        assertFalse(replacement.contains("Method = \"POST\""));
        assertFalse(replacement.contains("submitTransaction"));
        assertFalse(replacement.contains("setFaab"));
        assertFalse(replacement.contains("win probability"));
        assertFalse(replacement.contains("betting"));
        assertFalse(replacement.contains("pick'em"));
        assertTrue(replacement.contains("existing governed Lineup Advisor"));
        assertTrue(replacement.contains("does not create a new projection model"));
    }

    @Test
    void stagingRunsAfterFranchiseDetailAndBeforeFinalDashboardHostedTransforms() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf880 = staging.indexOf("& $bf880CoreTransform -CorePath $stagedCore");
        int bf881 = staging.indexOf("& $bf881CoreTransform -CorePath $stagedCore");
        int dashboardVisual = staging.indexOf("& $bf837DashboardTransform -DashboardPath $DashboardPath");

        assertTrue(bf880 >= 0);
        assertTrue(bf881 > bf880);
        assertTrue(dashboardVisual > bf881);
        assertTrue(staging.contains("butler-app-bf881-matchup-decision-first-transform.ps1"));
    }

    @Test
    void transformSourceIsAsciiOnly() throws Exception {
        String transform = source("scripts/butler-app-bf881-matchup-decision-first-transform.ps1");
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(transform));
    }

    private static String hereString(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        assertTrue(start >= 0, "start marker missing: " + startMarker);
        start += startMarker.length();
        int end = text.indexOf(endMarker, start);
        assertTrue(end > start, "end marker missing: " + endMarker);
        return text.substring(start, end);
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
        throw new IOException("BF-881 test could not locate " + relativePath);
    }
}
