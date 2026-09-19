package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLineupReviewLanguageBf847Test {

    @Test
    void myTeamIdleCopyUsesManagerLanguageWhileKeepingInternalRoute() throws Exception {
        String transform = source("scripts/butler-app-bf817-lineup-advisor-transform.ps1");

        assertTrue(transform.contains("Run a read-only lineup review when you want Butler to evaluate the current starters."));
        assertTrue(transform.contains(">Review Lineup</a>"));
        assertTrue(transform.contains("projection evidence is requested only after you request a lineup review."));
        assertTrue(transform.contains("href=\"/team/autofill\""));

        assertFalse(transform.contains("read-only AutoFill review"));
        assertFalse(transform.contains(">Run AutoFill</a>"));
        assertFalse(transform.contains("after you run AutoFill"));
    }

    @Test
    void managerGuardrailChecksMyTeamCopyLive() throws Exception {
        String script = source("scripts/butler-manager-guardrail-acceptance.ps1");

        assertTrue(script.contains("$root + '/team'"));
        assertTrue(script.contains("My Team: LINEUP_REVIEW_LANGUAGE_VERIFIED"));
        assertTrue(script.contains("'Review Lineup'"));
        assertTrue(script.contains("'href=\"/matchup/autofill\"'"));
        assertTrue(script.contains("'Run AutoFill'"));
        assertTrue(script.contains("'AutoFill review'"));
        assertTrue(script.contains("'run AutoFill'"));
    }

    @Test
    void internalAutofillImplementationNamesRemainUntouched() throws Exception {
        String transform = source("scripts/butler-app-bf817-lineup-advisor-transform.ps1");

        assertTrue(transform.contains("function ConvertTo-AutoFillHtml"));
        assertTrue(transform.contains("$AutoFill.Requested"));
        assertTrue(transform.contains("$AutoFill.Assignments"));
        assertTrue(transform.contains("AutoFillLineupOptimizer"));
    }

    @Test
    void presentationChangeAddsNoWriteBehavior() throws Exception {
        String transform = source("scripts/butler-app-bf817-lineup-advisor-transform.ps1");
        int start = transform.indexOf("$autoFillReplacement = @'");
        int end = transform.indexOf("'@", start);
        assertTrue(start >= 0 && end > start);
        String presentation = transform.substring(start, end);

        assertFalse(presentation.contains("Method = \"POST\""));
        assertFalse(presentation.contains("submitTransaction"));
        assertFalse(presentation.contains("Invoke-RestMethod"));
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
        throw new IOException("BF-847 test could not locate " + relativePath);
    }
}
