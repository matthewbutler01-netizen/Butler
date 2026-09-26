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
    void changesAndPartialReviewOpenTheFullLineupReview() throws Exception {
        String transform = source("scripts/butler-app-bf945-matchup-lineup-review-transform.ps1");

        assertTrue(transform.contains("Status = 'PARTIAL REVIEW'"));
        assertTrue(transform.contains("Status = 'CHANGES FOUND'"));
        assertTrue(transform.contains("ActionLabel = 'Open Lineup Review'"));
        assertTrue(transform.contains("ActionHref = '/team/autofill'"));
        assertTrue(transform.contains("Count -ne 2"));
    }

    @Test
    void idleGapAndNoChangeContractsRemainDistinct() throws Exception {
        String transform = source("scripts/butler-app-bf945-matchup-lineup-review-transform.ps1");

        assertTrue(transform.contains("Status = 'NOT REVIEWED'"));
        assertTrue(transform.contains("Status = 'EVIDENCE GAP'"));
        assertTrue(transform.contains("ActionHref = '/matchup/autofill'"));
        assertTrue(transform.contains("Status = 'NO CHANGES'"));
    }

    @Test
    void stagingRunsAfterTheLineupCompareReturnLoop() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf944 = staging.indexOf("& $bf944Transform -CorePath $stagedCore");
        int bf945 = staging.indexOf("& $bf945Transform -CorePath $stagedCore");
        int bf857 = staging.indexOf("& $bf857Transform -CorePath $stagedCore -DashboardPath $DashboardPath");

        assertTrue(bf944 >= 0, "BF-944 staging marker missing");
        assertTrue(bf945 > bf944, "BF-945 must run after BF-944");
        assertTrue(bf857 > bf945, "diagnostic timing must remain last");
    }

    @Test
    void liveAcceptanceChecksTheStateSpecificBridge() throws Exception {
        String acceptance = source("scripts/butler-weekly-matchup-acceptance.ps1");

        assertTrue(acceptance.contains("if ($hasChanges -or $hasPartialReview)"));
        assertTrue(acceptance.contains("href=\"/team/autofill\""));
        assertTrue(acceptance.contains("Open Lineup Review"));
        assertTrue(acceptance.contains("no-change Matchup state exposed an unnecessary Lineup Review action"));
    }

    @Test
    void opponentAndRichLineupCapabilitiesRemainRequired() throws Exception {
        String transform = source("scripts/butler-app-bf945-matchup-lineup-review-transform.ps1");

        assertTrue(transform.contains("Scout opponent"));
        assertTrue(transform.contains("Trade with opponent"));
        assertTrue(transform.contains("Compare this swap"));
        assertTrue(transform.contains("CurrentPoints"));
        assertTrue(transform.contains("CHANGES FIRST"));
        assertTrue(transform.contains("Back to Lineup Review"));
    }

    @Test
    void transformRemainsPresentationOnlyAndAscii() throws Exception {
        String transform = source("scripts/butler-app-bf945-matchup-lineup-review-transform.ps1");
        int safetyScan = transform.indexOf("if ($decision -match");
        assertTrue(safetyScan > 0, "BF-945 safety scan must remain present");
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
        throw new IOException("BF-945 test could not locate " + relativePath);
    }
}
