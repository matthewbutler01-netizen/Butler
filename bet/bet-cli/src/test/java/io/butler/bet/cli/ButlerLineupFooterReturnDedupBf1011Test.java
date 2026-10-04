package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLineupFooterReturnDedupBf1011Test {

    @Test
    void finalStagingRunsFooterNormalizationAfterSavedReview() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");
        int savedReview = staging.indexOf("butler-app-bf1005-matchup-saved-lineup-review-transform.ps1");
        int footer = staging.indexOf("butler-app-bf1011-lineup-footer-return-dedup-transform.ps1");

        assertTrue(savedReview >= 0);
        assertTrue(footer > savedReview);
        assertTrue(staging.contains("& $bf1011Transform -CorePath $stagedCore"));
    }

    @Test
    void transformScopesCanonicalizationToCompletedFooter() throws Exception {
        String transform = source("scripts/butler-app-bf1011-lineup-footer-return-dedup-transform.ps1");

        assertTrue(transform.contains("$refreshCount -ne 1"));
        assertTrue(transform.contains("$function.LastIndexOf($buttonRowStart, $refreshIndex"));
        assertTrue(transform.contains("$function.IndexOf($buttonRowEnd, $refreshIndex"));
        assertTrue(transform.contains("$canonicalFooter = $buttonRowStart + $refreshAnchor + $dashboardAnchor + $matchupAnchor + $teamAnchor + $buttonRowEnd"));
        assertTrue(transform.contains("Back to Dashboard"));
        assertTrue(transform.contains("Back to Matchup"));
        assertTrue(transform.contains("Back to My Team"));
        assertTrue(transform.contains("Refresh projection"));
        assertTrue(transform.contains("$installedFooter -cne $canonicalFooter"));
    }

    @Test
    void acceptanceChecksOnlyTheCompletedFooterRow() throws Exception {
        String acceptance = source("scripts/butler-bf1011-lineup-footer-return-dedup-acceptance.ps1");

        assertTrue(acceptance.contains("$footer = $function.Substring($footerStart, $footerEnd - $footerStart)"));
        assertTrue(acceptance.contains("$canonical = $buttonRowStart + $refresh + $dashboard + $matchup + $team + $buttonRowEnd"));
        assertTrue(acceptance.contains("$footer -cne $canonical"));
    }

    @Test
    void presentationCloseoutIncludesBf1011() throws Exception {
        String closeout = source("scripts/butler-presentation-closeout-acceptance.ps1");
        assertTrue(closeout.contains("Id = 'BF-1011'"));
        assertTrue(closeout.contains("butler-bf1011-lineup-footer-return-dedup-acceptance.ps1"));
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
        throw new IOException("BF-1011 test could not locate " + relativePath);
    }
}
