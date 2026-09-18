package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWeeklyMatchupLineupContextBf842Test {

    @Test
    void normalMatchupStaysProviderFreeUntilManagerRequestsAutofill() throws Exception {
        String transform = source("scripts/butler-app-bf840-weekly-matchup-transform.ps1");

        assertTrue(transform.contains("$path -eq \"/matchup\" -or $path -eq \"/matchup/autofill\""));
        assertTrue(transform.contains("$requestAutoFill = $path -eq \"/matchup/autofill\""));
        assertTrue(transform.contains("$bundleArguments = if ($requestAutoFill) { \"$LeagueId --team-bundle-autofill\" } else { \"$LeagueId --team-bundle\" }"));
        assertTrue(transform.contains("New-AutoFillIdleView"));
        assertTrue(transform.contains("ConvertTo-AutoFillView -Text"));
    }

    @Test
    void matchupLineupLinksStayInsideMatchupWorkspace() throws Exception {
        String transform = source("scripts/butler-app-bf840-weekly-matchup-transform.ps1");

        assertTrue(transform.contains("function ConvertTo-MatchupAutoFillHtml"));
        assertTrue(transform.contains("href=\"/team/autofill\"', 'href=\"/matchup/autofill\""));
        assertTrue(transform.contains("href=\"/team\"', 'href=\"/matchup\""));
        assertTrue(transform.contains("ConvertTo-MatchupAutoFillHtml -AutoFill $AutoFill"));
    }

    @Test
    void liveAcceptanceProvesIdleThenExplicitReview() throws Exception {
        String script = source("scripts/butler-weekly-matchup-acceptance.ps1");

        int idle = script.indexOf("$root + '/matchup'");
        int review = script.indexOf("$root + '/matchup/autofill'");
        assertTrue(idle >= 0 && review > idle,
            "BF-842 acceptance must prove idle Matchup before explicit AutoFill");

        for (String marker : new String[]{
                "NOT REVIEWED",
                "href=\"/matchup/autofill\"",
                "OPT_IN_REVIEW_VERIFIED",
                "GOVERNED_LINEUP_ADVISOR_RENDERED",
                "explicit Matchup AutoFill request remained in NOT REVIEWED state",
                "escaped to the My Team AutoFill route"
        }) {
            assertTrue(script.contains(marker), "BF-842 acceptance missing " + marker);
        }
    }

    @Test
    void noNewWriteOrPredictionBehaviorIsIntroduced() throws Exception {
        String transform = source("scripts/butler-app-bf840-weekly-matchup-transform.ps1");
        int safetyScan = transform.indexOf("$installedStart");
        assertTrue(safetyScan > 0, "BF-842 must preserve the BF-840 safety-scan boundary");
        String operational = transform.substring(0, safetyScan);

        assertFalse(operational.contains("Method = \"POST\""));
        assertFalse(operational.contains("submitTransaction"));
        assertFalse(operational.contains("setFaab"));
        assertFalse(operational.contains("win probability</"));
        assertFalse(operational.contains("predictedWinner"));
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
        throw new IOException("BF-842 test could not locate " + relativePath);
    }
}
