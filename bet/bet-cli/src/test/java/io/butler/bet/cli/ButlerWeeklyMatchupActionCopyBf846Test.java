package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWeeklyMatchupActionCopyBf846Test {

    @Test
    void matchupAdapterRewritesOnlyContextSpecificVisibleActions() throws Exception {
        String transform = source("scripts/butler-app-bf840-weekly-matchup-transform.ps1");

        int start = transform.indexOf("function ConvertTo-MatchupAutoFillHtml");
        int end = transform.indexOf("function ConvertTo-MatchupHtml", start);
        assertTrue(start >= 0 && end > start);
        String adapter = transform.substring(start, end);

        assertTrue(adapter.contains("href=\"/team/autofill\"', 'href=\"/matchup/autofill\""));
        assertTrue(adapter.contains("href=\"/team\"', 'href=\"/matchup\""));
        assertTrue(adapter.contains(">Run AutoFill</a>', '>Review Lineup</a>"));
        assertTrue(adapter.contains(">Back to My Team</a>', '>Back to Matchup</a>"));
    }

    @Test
    void standaloneTeamLineupAdvisorCopyRemainsUnchanged() throws Exception {
        String lineup = source("scripts/butler-app-bf817-lineup-advisor-transform.ps1");

        assertTrue(lineup.contains(">Run AutoFill</a>"));
        assertTrue(lineup.contains(">Back to My Team</a>"));
        assertFalse(lineup.contains(">Back to Matchup</a>"));
    }

    @Test
    void liveAcceptanceProvesContextCorrectLabels() throws Exception {
        String acceptance = source("scripts/butler-weekly-matchup-acceptance.ps1");

        assertTrue(acceptance.contains("Review Lineup"));
        assertTrue(acceptance.contains("Back to Matchup"));
        assertTrue(acceptance.contains("Run AutoFill"));
        assertTrue(acceptance.contains("Back to My Team"));
    }

    @Test
    void presentationChangeAddsNoProviderOrWriteBehavior() throws Exception {
        String transform = source("scripts/butler-app-bf840-weekly-matchup-transform.ps1");
        int start = transform.indexOf("function ConvertTo-MatchupAutoFillHtml");
        int end = transform.indexOf("function ConvertTo-MatchupHtml", start);
        String adapter = transform.substring(start, end);

        assertFalse(adapter.contains("Invoke-RestMethod"));
        assertFalse(adapter.contains("Invoke-WebRequest"));
        assertFalse(adapter.contains("Method = \"POST\""));
        assertFalse(adapter.contains("submitTransaction"));
        assertFalse(adapter.contains("AutoFillLineupOptimizer"));
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
        throw new IOException("BF-846 test could not locate " + relativePath);
    }
}
