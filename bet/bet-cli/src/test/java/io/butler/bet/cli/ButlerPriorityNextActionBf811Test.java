package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPriorityNextActionBf811Test {

    @Test
    void lineupEvidenceGapGivesRecoveryGuidanceWithoutPromisingImmediateRetry() throws Exception {
        String transform = source("scripts/butler-dashboard-bf810-priority-explanation-transform.ps1");

        assertTrue(transform.contains("What to do next"));
        assertTrue(transform.contains("Review your current starters manually."));
        assertTrue(transform.contains("Retry AutoFill after the missing weekly projection or provider evidence becomes available"));
        assertTrue(transform.contains("Butler will not guess in the meantime."));
        assertTrue(transform.contains("$primaryNextActionHref = \"/team\""));
        assertTrue(transform.contains("$primaryNextActionLabel = \"Review My Team\""));
    }

    @Test
    void existingPriorityStatesMapToManagerSafeNextActions() throws Exception {
        String transform = source("scripts/butler-dashboard-bf810-priority-explanation-transform.ps1");

        assertTrue(transform.contains("\"REFRESH AUTOFILL\""));
        assertTrue(transform.contains("\"AUTOFILL READY\""));
        assertTrue(transform.contains("\"NO CHANGES\""));
        assertTrue(transform.contains("\"CURRENT_AND_ACTIONABLE\""));
        assertTrue(transform.contains("\"CURRENT_REFRESH_RECOMMENDED\""));
        assertTrue(transform.contains("\"TRANSACTION_PENDING_DO_NOT_DUPLICATE\""));
        assertTrue(transform.contains("\"STALE_DO_NOT_ACT\""));
        assertTrue(transform.contains("\"NO_TRANSACTION_TO_ACT_ON\""));
        assertTrue(transform.contains("Open Trade Lab when you have a specific deal or target to evaluate."));
    }

    @Test
    void nextActionSurfaceUsesExistingManagerRoutesAndRemainsReadOnly() throws Exception {
        String transform = source("scripts/butler-dashboard-bf810-priority-explanation-transform.ps1");

        assertTrue(transform.contains("$(ConvertTo-HtmlText $primaryNextActionCopy)"));
        assertTrue(transform.contains("$(ConvertTo-HtmlText $primaryNextActionHref)"));
        assertTrue(transform.contains("$(ConvertTo-HtmlText $primaryNextActionLabel)"));
        assertFalse(transform.contains("Invoke-RestMethod"));
        assertFalse(transform.contains("BUTLER_FANTASYPROS_API_KEY"));
        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("AutoFillLineupOptimizer"));
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
        throw new IOException("BF-811 test could not locate " + relativePath);
    }
}
