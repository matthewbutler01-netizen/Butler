package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardBf654ScriptTest {

    @Test
    void dashboardUsesOnlyBf653ReadOnlyLookupForExplanation() throws Exception {
        String script = script();
        assertTrue(script.contains("function Invoke-ButlerReadOnlyExplanationLookup"));
        assertTrue(script.contains(":bet:bet-cli:sleeperLiveWaiverGovernedExplanationLookup"));
        assertTrue(script.contains("--args=$LeagueId $AuditId"));
        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverGovernedExplanationCapture"));
        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverFinalRecommendationBundle"));
        assertFalse(script.contains("Generate-Explanation"));
    }

    @Test
    void explanationReconcilesExactAuditTargetLineageAndTransaction() throws Exception {
        String script = script();
        assertTrue(script.contains("function Get-GovernedExplanationView"));
        assertTrue(script.contains("BF-627 audit id:"));
        assertTrue(script.contains("BF-603 market / BF-602 waiver snapshot:"));
        assertTrue(script.contains("Audited add / drop Sleeper ids:"));
        assertTrue(script.contains("BF-654 BLOCKED: BF-653 audit id disagrees with current BF-627 audit"));
        assertTrue(script.contains("BF-654 BLOCKED: BF-653 BF-603/BF-602 lineage disagrees with current BF-631 audit lineage"));
        assertTrue(script.contains("BF-654 BLOCKED: BF-653 audited ADD/DROP ids disagree with current audited transaction"));
        assertTrue(script.contains("summary BF-623 target identity disagrees with BF-653 lookup target identity"));
    }

    @Test
    void explanationDisplayIsPersistedOrExplicitlyUnavailableNeverInvented() throws Exception {
        String script = script();
        assertTrue(script.contains("EXPLANATION_READY"));
        assertTrue(script.contains("EXPLANATION_NOT_CAPTURED"));
        assertTrue(script.contains("Why this move?"));
        assertTrue(script.contains("Persisted BF-653 explanation"));
        assertTrue(script.contains("No persisted BF-653 explanation is available for this exact audit."));
        assertTrue(script.contains("BF-653 explanation ID:"));
        assertTrue(script.contains("BF-653 explanation type:"));
        assertTrue(script.contains("BF-653 evidence policy:"));
        assertTrue(script.contains("BF-653 evidence trace:"));
    }

    @Test
    void olderRankingAndWriteBoundariesRemainPresent() throws Exception {
        String script = script();
        assertTrue(script.contains("NOT A RANKING."));
        assertTrue(script.contains("Current governed ADD"));
        assertTrue(script.contains("Current governed DROP"));
        assertTrue(script.contains("Paired audited ADD"));
        assertTrue(script.contains("Paired audited DROP"));
        assertTrue(script.contains("READ ONLY."));
        assertFalse(script.contains("Sort-Object"));
    }

    private static String script() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve("scripts/butler-dashboard.ps1");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-654 test could not locate scripts/butler-dashboard.ps1");
    }
}
