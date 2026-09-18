package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWeeklyMatchupPlainLanguageBf848Test {

    @Test
    void verifiedAndFailClosedMatchupCopyUsesManagerLanguage() throws Exception {
        String transform = source("scripts/butler-app-bf840-weekly-matchup-transform.ps1");

        assertTrue(transform.contains("OPPONENT CONFIRMED"));
        assertTrue(transform.contains("Matchup details"));
        assertTrue(transform.contains("Opponent not confirmed"));
        assertTrue(transform.contains("MATCHUP DATA NEEDED"));
        assertTrue(transform.contains("Your Week $(ConvertTo-HtmlText $Matchup.Week) opponent is confirmed."));
        assertTrue(transform.contains("Butler could not confirm your current Sleeper opponent, so it will not guess or display one."));

        assertFalse(transform.contains("PAIRING VERIFIED"));
        assertFalse(transform.contains("EVIDENCE NEEDED"));
        assertFalse(transform.contains("Pairing evidence"));
        assertFalse(transform.contains("Opponent pairing unavailable"));
    }

    @Test
    void plainLanguagePassPreservesExactPairingAndFailClosedLogic() throws Exception {
        String transform = source("scripts/butler-app-bf840-weekly-matchup-transform.ps1");

        assertTrue(transform.contains("Get-TeamEvidenceBundleSection -Text $bundleText -Name \"MATCHUP\""));
        assertTrue(transform.contains("exact matchup frame does not match the bound roster frame"));
        assertTrue(transform.contains("Current Sleeper week is unavailable, so exact opponent pairing cannot be resolved."));
        assertTrue(transform.contains("will not guess or display one"));
        assertTrue(transform.contains("does not predict a winner"));
        assertTrue(transform.contains("'does not predict a winner',"));
        assertFalse(transform.contains("'not to predict a winner',"));
    }

    @Test
    void liveAcceptanceRejectsLegacyEngineeringCopy() throws Exception {
        String acceptance = source("scripts/butler-weekly-matchup-acceptance.ps1");

        assertTrue(acceptance.contains("Matchup copy: PLAIN_LANGUAGE_VERIFIED"));
        assertTrue(acceptance.contains("Matchup details"));
        assertTrue(acceptance.contains("PAIRING VERIFIED"));
        assertTrue(acceptance.contains("EVIDENCE NEEDED"));
        assertTrue(acceptance.contains("Pairing evidence"));
        assertTrue(acceptance.contains("Opponent pairing unavailable"));
    }

    @Test
    void presentationChangeAddsNoProviderOrWriteBehavior() throws Exception {
        String transform = source("scripts/butler-app-bf840-weekly-matchup-transform.ps1");
        int start = transform.indexOf("function ConvertTo-MatchupOpponentContextHtml");
        int end = transform.indexOf("function ConvertTo-LeagueHtml", start);
        assertTrue(start >= 0 && end > start);
        String presentation = transform.substring(start, end);

        assertFalse(presentation.contains("Invoke-RestMethod"));
        assertFalse(presentation.contains("Invoke-WebRequest"));
        assertFalse(presentation.contains("Method = \"POST\""));
        assertFalse(presentation.contains("submitTransaction"));
        assertFalse(presentation.contains("setFaab"));
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
        throw new IOException("BF-848 test could not locate " + relativePath);
    }
}
