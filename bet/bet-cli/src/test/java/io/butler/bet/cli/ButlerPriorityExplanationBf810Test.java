package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPriorityExplanationBf810Test {

    @Test
    void explanationFollowsOrderedPriorityOneSignal() throws Exception {
        String transform = source("scripts/butler-dashboard-bf810-priority-explanation-transform.ps1");

        assertTrue(transform.contains("$priorityOne = if ($orderedPrioritySignals.Count -gt 0)"));
        assertTrue(transform.contains("Why lineup is priority 01"));
        assertTrue(transform.contains("Why waiver is priority 01"));
        assertTrue(transform.contains("Why trade is priority 01"));
        assertTrue(transform.contains("\"EVIDENCE GAP\""));
        assertTrue(transform.contains("$primaryExplanationCopy = $whyCopy"));
        assertTrue(transform.contains("$primaryExplanationCopy = $tradeSignalCopy"));
    }

    @Test
    void explanationPanelUsesPriorityAwareCopyInsteadOfWaiverOnlyCopy() throws Exception {
        String transform = source("scripts/butler-dashboard-bf810-priority-explanation-transform.ps1");

        assertTrue(transform.contains("$(ConvertTo-HtmlText $primaryExplanationTitle)"));
        assertTrue(transform.contains("$(ConvertTo-HtmlText $primaryExplanationCopy)"));
        assertTrue(transform.contains("priority 01 explanation derivation"));
        assertTrue(transform.contains("Command Center explanation panel"));
    }

    @Test
    void stagingRunsAfterBf809ReaderHardening() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf809 = staging.indexOf("& $bf809Transform -DashboardPath $DashboardPath");
        int bf810 = staging.indexOf("& $bf810Transform -DashboardPath $DashboardPath");
        assertTrue(bf809 >= 0, "BF-809 staging must remain present");
        assertTrue(bf810 > bf809, "BF-810 must run after BF-809 establishes the final lineup signal");
        assertTrue(staging.contains("butler-dashboard-bf810-priority-explanation-transform.ps1"));
    }

    @Test
    void remainsReadOnlyAndProviderFree() throws Exception {
        String transform = source("scripts/butler-dashboard-bf810-priority-explanation-transform.ps1");

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
        throw new IOException("BF-810 test could not locate " + relativePath);
    }
}
