package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerUserInformationHierarchyBf793Test {

    @Test
    void publicPresentationKeepsTechnicalTraceOutOfNormalScanPath() throws Exception {
        String cache = source("scripts/butler-app-request-worker-cache.ps1");

        assertTrue(cache.contains("Several waiver options were too close to separate confidently"));
        assertTrue(cache.contains("This explanation was saved with the decision"));
        assertTrue(cache.contains("Advanced comparison details"));
        assertTrue(cache.contains("Troubleshooting data only. It does not change Butler''s recommendation."));
        assertTrue(cache.contains("Advanced technical record"));
        assertTrue(cache.contains("You do not need these IDs to use Butler."));
        assertTrue(cache.contains("Advanced manual command"));
        assertTrue(cache.contains("Butler will never run it from this page."));
        assertTrue(cache.contains("Recent Sleeper activity:"));
        assertTrue(cache.contains("Review type"));
        assertTrue(cache.contains("Check again after the next value update"));
        assertTrue(cache.contains("Butler needs another value update before it can show a meaningful trend"));
        assertTrue(cache.contains("PLAYER REVIEW."));
        assertTrue(cache.contains("Player IDs (add / drop):"));
        assertTrue(cache.contains("Value snapshot ID:"));
        assertTrue(cache.contains("Waiver snapshot ID:"));
    }

    @Test
    void exactTraceDataRemainsInSourceAndPresentationOnlyChangesHierarchy() throws Exception {
        String dashboard = source("scripts/butler-dashboard.ps1");
        String history = source("scripts/butler-decision-history.ps1");

        assertTrue(dashboard.contains("<details open><summary>Comparator traceability</summary>"));
        assertTrue(dashboard.contains("Candidate-supported comparators:"));
        assertTrue(dashboard.contains("Current audit ID:"));
        assertTrue(dashboard.contains("BF-603 / BF-602:"));
        assertTrue(history.contains("Audit: $(ConvertTo-HtmlText $entry.AuditId)"));
        assertTrue(history.contains("BF-603 market: $(ConvertTo-HtmlText $entry.MarketSnapshotId)"));
        assertTrue(history.contains("BF-602 waiver: $(ConvertTo-HtmlText $entry.WaiverSnapshotId)"));

        String cache = source("scripts/butler-app-request-worker-cache.ps1");
        assertTrue(cache.contains("<details><summary>Advanced comparison details</summary>"));
        assertFalse(cache.contains("delete") && cache.contains("AuditId"));
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
        throw new IOException("BF-793 test could not locate " + relativePath);
    }
}
