package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDecisionDetailExplanationFirstBf877Test {

    @Test
    void decisionFirstScanKeepsTechnicalEvidenceBehindDisclosure() throws Exception {
        String detail = source("scripts/butler-decision-detail.ps1");

        int decision = detail.indexOf("<div class=\"eyebrow\">Recorded waiver decision</div>");
        int evidence = detail.indexOf("<details><summary>Decision evidence</summary>", decision);
        assertTrue(decision >= 0, "recorded decision marker must exist");
        assertTrue(evidence > decision, "decision evidence must follow the first scan");

        String firstScan = detail.substring(decision, evidence);
        assertTrue(firstScan.contains("$decisionLabel"));
        assertTrue(firstScan.contains("$(ConvertTo-HtmlText $capturedLabel)"));
        assertFalse(firstScan.contains("Provider frame"));
        assertFalse(firstScan.contains("Selection state"));
        assertFalse(firstScan.contains("Recommendation state"));
        assertFalse(firstScan.contains("Audit:"));
        assertFalse(firstScan.contains("BF-603"));
        assertFalse(firstScan.contains("BF-602"));
        assertFalse(firstScan.contains("Sleeper ids"));
        assertFalse(firstScan.contains("$Entry.IntegrityState"));
    }

    @Test
    void savedExplanationTextIsPrimaryAndMetadataIsDisclosureOnly() throws Exception {
        String detail = source("scripts/butler-decision-detail.ps1");

        int explanation = detail.indexOf("<div class=\"eyebrow\">Saved explanation</div>");
        int details = detail.indexOf("<details><summary>Explanation details</summary>", explanation);
        assertTrue(explanation >= 0, "saved explanation marker must exist");
        assertTrue(details > explanation, "explanation metadata must follow explanation text");

        String firstScan = detail.substring(explanation, details);
        assertTrue(firstScan.contains("Why this decision?"));
        assertTrue(firstScan.contains("$Explanation.ExplanationText"));
        assertFalse(firstScan.contains("Explanation type"));
        assertFalse(firstScan.contains("Explanation captured"));
        assertFalse(firstScan.contains("Explanation id"));
        assertFalse(firstScan.contains("Evidence policy"));
        assertFalse(firstScan.contains("Evidence trace"));

        String technical = detail.substring(details);
        assertTrue(technical.contains("Explanation type"));
        assertTrue(technical.contains("$explanationCapturedLabel"));
        assertTrue(technical.contains("$Explanation.ExplanationId"));
        assertTrue(technical.contains("Captured UTC: $(ConvertTo-HtmlText $Explanation.Captured)"));
        assertTrue(technical.contains("Evidence policy: $(ConvertTo-HtmlText $Explanation.EvidencePolicy)"));
        assertTrue(technical.contains("Evidence trace: $(ConvertTo-HtmlText $Explanation.EvidenceTrace)"));
    }

    @Test
    void missingExplanationStaysFailClosedWithoutImplementationCopy() throws Exception {
        String detail = source("scripts/butler-decision-detail.ps1");

        assertTrue(detail.contains("No saved explanation"));
        assertTrue(detail.contains("No explanation was saved with this historical decision."));
        assertTrue(detail.contains("Butler will not reconstruct or generate reasoning that was not persisted with the decision."));
        assertTrue(detail.contains("NOT SAVED"));

        assertFalse(detail.contains("BF-653 reports EXPLANATION_NOT_CAPTURED for this immutable audit."));
        assertFalse(detail.contains("LEGACY / NOT CAPTURED"));
    }

    @Test
    void exactLookupAndReconciliationRemainUntouched() throws Exception {
        String detail = source("scripts/butler-decision-detail.ps1");

        assertTrue(detail.contains("Get-ExactDecisionHistoryEntry -History $history -AuditId $auditId"));
        assertTrue(detail.contains("Assert-DecisionExplanationReconciled -Entry $entry -Explanation $explanation"));
        assertTrue(detail.contains("$Explanation.AuditId -cne $Entry.AuditId"));
        assertTrue(detail.contains("$Explanation.MarketSnapshotId -cne $Entry.MarketSnapshotId"));
        assertTrue(detail.contains("$Explanation.WaiverSnapshotId -cne $Entry.WaiverSnapshotId"));
        assertTrue(detail.contains("$Explanation.AddSleeperId -cne $Entry.AddSleeperId"));
        assertTrue(detail.contains("$Explanation.DropSleeperId -cne $Entry.DropSleeperId"));
    }

    @Test
    void managerCopyHidesImplementationIdentifiersButRawEvidenceRemains() throws Exception {
        String detail = source("scripts/butler-decision-detail.ps1");

        assertTrue(detail.contains("Loading this recorded waiver decision and its saved explanation."));
        assertTrue(detail.contains("Decision Detail reads the selected recorded decision and its saved explanation only."));
        assertTrue(detail.contains("BF-603 market: $(ConvertTo-HtmlText $Entry.MarketSnapshotId)"));
        assertTrue(detail.contains("BF-602 waiver: $(ConvertTo-HtmlText $Entry.WaiverSnapshotId)"));

        assertFalse(detail.contains("Validating the exact immutable BF-627 audit in BF-628 before reading its persisted BF-653 explanation."));
        assertFalse(detail.contains("This detail view is reconciled to one exact BF-628 integrity-verified BF-627 audit."));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-877 test could not locate " + relativePath);
    }
}
