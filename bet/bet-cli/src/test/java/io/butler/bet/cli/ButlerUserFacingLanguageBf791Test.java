package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerUserFacingLanguageBf791Test {

    @Test
    void everyPublicHtmlResponseCrossesOneUserFacingPresentationBoundary() throws Exception {
        String cache = source("scripts/butler-app-request-worker-cache.ps1");

        assertTrue(cache.contains("function ConvertTo-ButlerUserFacingHtml"));
        assertTrue(cache.contains("$ContentType -match '^text/html'"));
        assertTrue(cache.contains("$Body = ConvertTo-ButlerUserFacingHtml -Html $Body"));
        assertTrue(cache.contains("public response boundary count"));
        assertTrue(cache.contains("public HTML encoding boundary count"));
        assertTrue(cache.contains("<pre\\b[^>]*>.*?</pre>"));
    }

    @Test
    void presentationBoundaryTranslatesKnownEngineeringLeaks() throws Exception {
        String cache = source("scripts/butler-app-request-worker-cache.ps1");

        assertTrue(cache.contains("'HISTORY_INTEGRITY_VERIFIED' = 'Verified'"));
        assertTrue(cache.contains("'LINEAGE_AND_IDENTITY_VERIFIED' = 'Verified'"));
        assertTrue(cache.contains("'NO_HISTORICAL_FINALIST' = 'No prior recommendation'"));
        assertTrue(cache.contains("'NO_GOVERNED_TRANSACTION' = 'No move recommended'"));
        assertTrue(cache.contains("'NOT_EVALUATED' = 'Not checked'"));
        assertTrue(cache.contains("'FLEXIBLE_BALANCED' = 'Balanced'"));
        assertTrue(cache.contains("'INCONCLUSIVE' = 'No clear recommendation'"));
        assertTrue(cache.contains("'Immutable governed waiver audits' = 'Waiver decision history'"));
        assertTrue(cache.contains("'Governed trade evaluation' = 'Trade evaluation'"));
        assertTrue(cache.contains("'Strategic veto' = 'Deal-breaker check'"));
        assertTrue(cache.contains("'Flexible pressure' = 'Roster flexibility'"));
        assertTrue(cache.contains("'Material-loss veto evidence' = 'Deal-breakers'"));
        assertTrue(cache.contains("'Technical governed output' = 'Technical details'"));
    }

    @Test
    void presentationBoundaryAlsoCatchesFutureEnumAndEngineeringVocabulary() throws Exception {
        String cache = source("scripts/butler-app-request-worker-cache.ps1");

        assertTrue(cache.contains("function ConvertTo-ButlerDisplayToken"));
        assertTrue(cache.contains("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+"));
        assertTrue(cache.contains("\\bBF-\\d+(?:-bound)?\\b"));
        assertTrue(cache.contains("\\bgoverned\\s+"));
        assertTrue(cache.contains("\\bpersisted\\b"));
        assertTrue(cache.contains("\\bbound\\b"));
        assertTrue(cache.contains("\\blineage\\b"));
        assertTrue(cache.contains("Decision history</div>"));
        assertTrue(cache.contains("Team ID\\s+"));
    }

    @Test
    void normalReadOnlyScreensUseOneProductPromise() throws Exception {
        String cache = source("scripts/butler-app-request-worker-cache.ps1");

        assertTrue(cache.contains("Butler will never make roster changes or submit a Sleeper transaction from this screen."));
        assertTrue(cache.contains("READ ONLY\\.</span>.*?</section>"));
    }

    @Test
    void normalizationDoesNotRenameInternalDecisionContracts() throws Exception {
        String dashboard = source("scripts/butler-dashboard.ps1");
        String history = source("scripts/butler-decision-history.ps1");
        String trade = source("scripts/butler-trade-lab.ps1");

        assertTrue(dashboard.contains("LIVE_ACTIONABLE_VERIFIED"));
        assertTrue(dashboard.contains("LATEST_EVIDENCE_LINEAGE_VERIFIED"));
        assertTrue(history.contains("NO_GOVERNED_TRANSACTION"));
        assertTrue(trade.contains("Strategic veto:"));
        assertFalse(dashboard.contains("ConvertTo-ButlerUserFacingHtml"));
        assertFalse(history.contains("ConvertTo-ButlerUserFacingHtml"));
        assertFalse(trade.contains("ConvertTo-ButlerUserFacingHtml"));
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
        throw new IOException("BF-791 test could not locate " + relativePath);
    }
}
