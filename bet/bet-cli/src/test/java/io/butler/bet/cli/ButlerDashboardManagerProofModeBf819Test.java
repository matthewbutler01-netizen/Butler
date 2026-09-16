package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardManagerProofModeBf819Test {

    @Test
    void dashboardLeadsWithManagerDecisionQueue() throws Exception {
        String transform = source("scripts/butler-dashboard-bf819-manager-proof-mode-transform.ps1");

        assertTrue(transform.contains("What matters now"));
        assertTrue(transform.contains("1 item needs your attention."));
        assertTrue(transform.contains("Your decision queue"));
        assertTrue(transform.contains("Your lineup recommendation is out of date"));
        assertTrue(transform.contains("Your roster or projection data changed since the last lineup review."));
        assertTrue(transform.contains("Refresh Lineup"));
        assertTrue(transform.contains("No waiver move proven"));
        assertTrue(transform.contains("No active trade decision"));
    }

    @Test
    void managerVisualPolishSeparatesHeroMetadataAndDecisionCards() throws Exception {
        String transform = source("scripts/butler-dashboard-bf819-manager-proof-mode-transform.ps1");

        assertTrue(transform.contains(".manager-hero .command-meta{display:flex;gap:9px;flex-wrap:wrap;align-items:center;margin-top:18px}"));
        assertTrue(transform.contains(".manager-hero .command-meta span{display:inline-flex;align-items:center;padding:6px 10px"));
        assertTrue(transform.contains(".manager-decision-stack{display:grid;gap:16px}"));
        assertTrue(transform.contains("<div class=\"command-meta\"><span>$(ConvertTo-HtmlText $target)</span><span>Read-only manager view</span><span>Saved decisions remain traceable</span></div>"));
    }

    @Test
    void detailedGovernanceMovesBehindExpandableProofMode() throws Exception {
        String transform = source("scripts/butler-dashboard-bf819-manager-proof-mode-transform.ps1");

        assertTrue(transform.contains("<details id=\"decision-details\" class=\"proof-mode\">"));
        assertTrue(transform.contains("View decision details"));
        assertTrue(transform.contains("Why Butler says this"));
        assertTrue(transform.contains("What changed"));
        assertTrue(transform.contains("Evidence used"));
        assertTrue(transform.contains("Saved decision"));
        assertTrue(transform.contains("$primaryEvidenceHtml"));
        assertTrue(transform.contains("$decisionPackageRecord"));
        assertTrue(transform.contains("$decisionPackageTrust"));
    }

    @Test
    void finalManagerSurfaceDoesNotReRenderLegacyDiagnosticPanels() throws Exception {
        String transform = source("scripts/butler-dashboard-bf819-manager-proof-mode-transform.ps1");
        int returnStart = transform.indexOf("$newReturn = @'");
        assertTrue(returnStart >= 0);
        String finalSurface = transform.substring(returnStart);

        assertFalse(finalSurface.contains("Priority 01 decision package"));
        assertFalse(finalSurface.contains("Can I trust this decision frame?"));
        assertFalse(finalSurface.contains("Decision record</div>"));
        assertFalse(finalSurface.contains("Saved lineup frame needs refresh"));
        assertFalse(finalSurface.contains("Run AutoFill again"));
    }

    @Test
    void managerSurfacePreservesReadOnlyAndTraceabilityBoundaries() throws Exception {
        String transform = source("scripts/butler-dashboard-bf819-manager-proof-mode-transform.ps1");

        assertTrue(transform.contains("Butler is read only."));
        assertTrue(transform.contains("Recommendations stay reviewable and traceable; roster actions remain yours."));
        assertTrue(transform.contains("Butler does not change your Sleeper roster or submit lineup, waiver, or trade transactions from this Dashboard."));
        assertTrue(transform.contains("presentation-only"));
        assertTrue(transform.contains("Invoke-RestMethod|Invoke-ButlerReadOnly|Method = \"POST\"|AutoFillLineupOptimizer|BUTLER_FANTASYPROS_API_KEY"));
    }

    @Test
    void bf818StagesBf819AfterWaiverAdvisor() throws Exception {
        String bf818 = source("scripts/butler-dashboard-bf818-waiver-advisor-transform.ps1");

        assertTrue(bf818.contains("butler-dashboard-bf819-manager-proof-mode-transform.ps1"));
        assertTrue(bf818.contains("& $bf819Transform -DashboardPath $DashboardPath"));
        assertTrue(bf818.indexOf("[System.IO.File]::WriteAllText($DashboardPath") < bf818.indexOf("& $bf819Transform -DashboardPath $DashboardPath"));
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
        throw new IOException("BF-819 test could not locate " + relativePath);
    }
}
