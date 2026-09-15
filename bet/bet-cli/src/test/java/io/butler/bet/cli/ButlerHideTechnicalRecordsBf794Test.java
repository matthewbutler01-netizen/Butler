package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerHideTechnicalRecordsBf794Test {

    @Test
    void normalUserFacingHtmlRemovesTechnicalRecordBlocks() throws Exception {
        String cache = source("scripts/butler-app-request-worker-cache.ps1");

        assertTrue(cache.contains("BF-794 removes technical record disclosures from normal user-facing HTML"));
        assertTrue(cache.contains("<summary>Advanced technical record</summary>.*?</details>"));
        assertTrue(cache.contains("[regex]::Replace"));
    }

    @Test
    void underlyingTechnicalEvidenceRemainsAvailableInSourceRecords() throws Exception {
        String history = source("scripts/butler-decision-history.ps1");
        String detail = source("scripts/butler-decision-detail.ps1");

        assertTrue(history.contains("Audit: $(ConvertTo-HtmlText $entry.AuditId)"));
        assertTrue(history.contains("BF-603 market: $(ConvertTo-HtmlText $entry.MarketSnapshotId)"));
        assertTrue(history.contains("BF-602 waiver: $(ConvertTo-HtmlText $entry.WaiverSnapshotId)"));
        assertTrue(detail.contains("$Explanation.ExplanationId"));
        assertTrue(detail.contains("$Entry.AuditId"));
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
        throw new IOException("BF-794 test could not locate " + relativePath);
    }
}
