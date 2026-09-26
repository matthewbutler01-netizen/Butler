package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLineupMatchupReturnBf946Test {

    @Test
    void completedLineupReviewLinksBackToMatchupAndMyTeam() throws Exception {
        String transform = source("scripts/butler-app-bf946-lineup-matchup-return-transform.ps1");

        assertTrue(transform.contains("href=`\"/matchup`\">Back to Matchup</a>"));
        assertTrue(transform.contains("href=`\"/team`\">Back to My Team</a>"));
        assertTrue(transform.contains("href=`\"/team/autofill`\">Refresh projection</a>"));
        assertTrue(transform.contains("must expose exactly one Back to Matchup action"));
    }

    @Test
    void stagingRunsAfterMatchupBridgeAndBeforeDiagnostics() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf945 = staging.indexOf("& $bf945Transform -CorePath $stagedCore");
        int bf946 = staging.indexOf("& $bf946Transform -CorePath $stagedCore");
        int bf857 = staging.indexOf("& $bf857Transform -CorePath $stagedCore -DashboardPath $DashboardPath");

        assertTrue(bf945 >= 0, "BF-945 staging marker missing");
        assertTrue(bf946 > bf945, "BF-946 must run after BF-945");
        assertTrue(bf857 > bf946, "diagnostic timing must remain after BF-946");
        assertTrue(staging.contains("butler-app-bf946-lineup-matchup-return-transform.ps1"));
    }

    @Test
    void priorWeeklyDecisionLoopsRemainRequired() throws Exception {
        String transform = source("scripts/butler-app-bf946-lineup-matchup-return-transform.ps1");

        assertTrue(transform.contains("ActionLabel = 'Open Lineup Review'"));
        assertTrue(transform.contains("ActionHref = '/team/autofill'"));
        assertTrue(transform.contains("Back to Lineup Review"));
        assertTrue(transform.contains("Compare this swap"));
        assertTrue(transform.contains("CHANGES FIRST"));
    }

    @Test
    void weeklyAcceptanceTracksReturnContractWithoutAnotherProviderRequest() throws Exception {
        String acceptance = source("scripts/butler-weekly-matchup-acceptance.ps1");

        assertTrue(acceptance.contains("butler-app-bf946-lineup-matchup-return-transform.ps1"));
        assertTrue(acceptance.contains("BF946_MATCHUP_RETURN_CONTRACT_VERIFIED"));
        assertTrue(acceptance.contains("href=`\"/matchup`\">Back to Matchup</a>"));
        assertTrue(acceptance.contains("Back to Lineup Review"));

        int bridgeRuntime = acceptance.indexOf("$review = Invoke-Get -Url ($root + '/matchup/autofill')");
        assertTrue(bridgeRuntime >= 0, "existing explicit Matchup lineup review runtime check missing");
        assertFalse(acceptance.contains("$root + '/team/autofill'"),
                "BF-946 acceptance must not add another provider-backed Lineup Review request");
    }

    @Test
    void transformRemainsPresentationOnlyAndAscii() throws Exception {
        String transform = source("scripts/butler-app-bf946-lineup-matchup-return-transform.ps1");
        int safetyScan = transform.indexOf("foreach ($forbidden in @(");
        assertTrue(safetyScan > 0, "BF-946 safety scan must remain present");
        String operational = transform.substring(0, safetyScan);

        assertFalse(operational.contains("Invoke-RestMethod"));
        assertFalse(operational.contains("Invoke-WebRequest"));
        assertFalse(operational.contains("Method = \"POST\""));
        assertFalse(operational.contains("submitTransaction"));
        assertFalse(operational.contains("setFaab"));
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(transform));
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
        throw new IOException("BF-946 test could not locate " + relativePath);
    }
}
