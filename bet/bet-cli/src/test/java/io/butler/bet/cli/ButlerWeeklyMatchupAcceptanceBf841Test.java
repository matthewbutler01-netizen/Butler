package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWeeklyMatchupAcceptanceBf841Test {

    @Test
    void acceptanceDrivesLiveMatchupJourneyWithoutHardCodedTeams() throws Exception {
        String script = source("scripts/butler-weekly-matchup-acceptance.ps1");

        for (String marker : new String[]{
                "Butler Weekly Matchup end-to-end acceptance (BF-841)",
                "BF-840 exact-pairing sync",
                ":bet:bet-cli:sleeperCurrentWeekMatchupSync",
                "BF840_CURRENT_WEEK_EVIDENCE_VERIFIED",
                "/matchup",
                "Matchup bundle: SINGLE_JVM_COMPOSITION_VERIFIED",
                "Lineup idle: OPT_IN_REVIEW_VERIFIED",
                "Action copy: MATCHUP_CONTEXT_VERIFIED",
                "EXACT_PAIRING_RENDERED",
                "GOVERNED_LINEUP_ADVISOR_RENDERED",
                "GOVERNED_OPPONENT_CONTEXT_RENDERED",
                "Working tree: CLEAN",
                "BF-841 RESULT: COMPLETE"
        }) {
            assertTrue(script.contains(marker), "BF-841 acceptance missing " + marker);
        }

        assertFalse(script.contains("a75ccbfa-18b4-4e02-9d21-ccb0356568cf"));
        assertFalse(script.contains("1312110516008677376"));
        assertFalse(script.contains("mbutler0624"));
    }

    @Test
    void acceptanceIsGetOnlyAndOwnsItsProcessCleanup() throws Exception {
        String script = source("scripts/butler-weekly-matchup-acceptance.ps1");
        String cmd = source("scripts/butler-weekly-matchup-acceptance.cmd");

        assertTrue(script.contains("$request.Method = 'GET'"));
        assertTrue(script.contains("one explicit Butler-local matchup evidence sync"));
        assertTrue(script.contains("GET-only local Butler requests"));
        assertTrue(script.contains("/refresh excluded"));
        assertTrue(script.contains("BUTLER_APP_DATA_DIR"));
        assertTrue(script.contains("app-league.txt"));
        assertTrue(script.contains("taskkill /PID $Process.Id /T /F"));
        assertTrue(script.contains("running-port-{0}.txt"));
        assertTrue(script.contains("git status --porcelain=v1 --untracked-files=all"));
        assertTrue(cmd.contains("butler-weekly-matchup-acceptance.ps1"));

        assertFalse(script.contains("Method = 'POST'"));
        assertFalse(script.contains("Method = \"POST\""));
        assertFalse(script.contains("Invoke-RestMethod"));
        assertFalse(script.contains("Invoke-WebRequest"));
        assertFalse(script.contains("submitTransaction"));
        assertFalse(script.contains("setFaab"));
        assertFalse(script.contains("/refresh'"));
        assertFalse(script.contains("/refresh\""));
    }

    @Test
    void acceptanceFailsClosedOnUnavailablePairingBlockedSurfaceOrGamblingCopy() throws Exception {
        String script = source("scripts/butler-weekly-matchup-acceptance.ps1");

        assertTrue(script.contains("Opponent not confirmed"));
        assertTrue(script.contains("Butler Weekly Matchup view blocked"));
        assertTrue(script.contains("exact user-versus-opponent matchup headline was not rendered"));
        assertTrue(script.contains("repository must be clean before acceptance"));
        assertTrue(script.contains("repository became dirty during acceptance"));
        assertTrue(script.contains("pick''em"));
        assertTrue(script.contains("moneyline"));
        assertTrue(script.contains("sportsbook"));
        assertTrue(script.contains("betting odds"));
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
        throw new IOException("BF-841 test could not locate " + relativePath);
    }
}
