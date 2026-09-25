package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerMatchupLineupReviewBridgeBf945Test {

    @Test
    void readyChangeAndPartialStatesBridgeToMyTeamLineupReview() throws Exception {
        String transform = source("scripts/butler-app-bf945-matchup-lineup-review-bridge-transform.ps1");

        assertTrue(transform.contains("Status = 'CHANGES FOUND'"));
        assertTrue(transform.contains("Status = 'PARTIAL REVIEW'"));
        assertTrue(transform.contains("ActionLabel = 'Open Lineup Review'"));
        assertTrue(transform.contains("ActionHref = '/team/autofill'"));
    }

    @Test
    void requestAndRetryStatesKeepExistingMatchupAutofillRoute() throws Exception {
        String transform = source("scripts/butler-app-bf945-matchup-lineup-review-bridge-transform.ps1");

        assertTrue(transform.contains("ActionLabel = 'Review Lineup'"));
        assertTrue(transform.contains("ActionLabel = 'Retry Lineup Review'"));
        assertTrue(transform.contains("ActionHref = '/matchup/autofill'"));
    }

    @Test
    void noChangeStateRemainsActionFree() throws Exception {
        String transform = source("scripts/butler-app-bf945-matchup-lineup-review-bridge-transform.ps1");

        assertTrue(transform.contains("Title = 'Keep the current lineup'"));
        assertTrue(transform.contains("no-change Matchup state must remain action-free"));
    }

    @Test
    void preservesExistingOpponentActionsAndReadOnlyBoundary() throws Exception {
        String transform = source("scripts/butler-app-bf945-matchup-lineup-review-bridge-transform.ps1");

        assertTrue(transform.contains("href=\"/team\">Open My Team</a>"));
        assertTrue(transform.contains("href=\"/franchise?id=$opponentHrefId\">Scout opponent</a>"));
        assertTrue(transform.contains("href=\"/trade?opponent=$opponentHrefId\">Trade with opponent</a>"));
        assertFalse(transform.contains("Invoke-Bf742DashboardWorkerRead"));
        assertFalse(transform.contains("AutoFillLineupOptimizer"));
    }

    @Test
    void stagesAfterBf944AndBeforeDiagnosticTiming() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf944 = staging.indexOf("& $bf944Transform -CorePath $stagedCore");
        int bf945 = staging.indexOf("& $bf945Transform -CorePath $stagedCore");
        int bf857 = staging.indexOf("# BF-857: diagnostic-only inner-core timing");

        assertTrue(bf944 >= 0, "BF-944 staging marker missing");
        assertTrue(bf945 > bf944, "BF-945 must run after BF-944");
        assertTrue(bf857 > bf945, "BF-857 timing must remain after BF-945");
    }

    @Test
    void transformRemainsAscii() throws Exception {
        String transform = source("scripts/butler-app-bf945-matchup-lineup-review-bridge-transform.ps1");
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
        throw new IOException("BF-945 test could not locate " + relativePath);
    }
}
