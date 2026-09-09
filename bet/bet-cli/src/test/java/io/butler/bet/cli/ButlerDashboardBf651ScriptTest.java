package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardBf651ScriptTest {

    @Test
    void currentDropUsesOnlyExactAuditedSleeperIdentity() throws Exception {
        String script = script();
        assertTrue(script.contains("function Get-CurrentGovernedDropView"));
        assertTrue(script.contains("$drop = ConvertTo-PlayerView (Get-LineValue -Text $Summary -Label \"DROP:\")"));
        assertTrue(script.contains("$drop.SleeperId -notmatch '^[0-9]+$'"));
        assertTrue(script.contains("Where-Object { $_.SleeperId -ceq $drop.SleeperId }"));
        assertTrue(script.contains("$matches.Count -ne 1"));
        assertTrue(script.contains("must resolve exactly once in BF-610 target roster"));
        assertFalse(script.contains("drop.Name -eq"));
        assertFalse(script.contains("Resolve-DropByName"));
    }

    @Test
    void currentDropReconcilesSummaryTargetToVerifiedAndRawBf610Target() throws Exception {
        String script = script();
        assertTrue(script.contains("Get-Bf623TargetView -Text $RosterContext -BoundaryName \"BF-651\""));
        assertTrue(script.contains("Get-Bf623TargetView -Text $Summary -BoundaryName \"BF-651\""));
        assertTrue(script.contains("summary BF-623 target identity disagrees with BF-610 BF-623 target identity"));
        assertTrue(script.contains("Get-LineValue -Text $RosterContext -Label \"Sleeper league:\""));
        assertTrue(script.contains("Get-LineValue -Text $RosterContext -Label \"Exact target roster id:\""));
        assertTrue(script.contains("Target-roster context state:"));
        assertTrue(script.contains("READY_CONTEXT_ONLY"));
        assertTrue(script.contains("BF-610 BF-623 target identity disagrees with BF-610 raw roster context"));
    }

    @Test
    void currentDropMarkerIsLimitedToLiveActionableGovernedStates() throws Exception {
        String script = script();
        assertTrue(script.contains("$state -ceq \"CURRENT_AND_ACTIONABLE\" -or $state -ceq \"CURRENT_REFRESH_RECOMMENDED\""));
        assertTrue(script.contains("Active = $false"));
        assertTrue(script.contains("LIVE_ACTIONABLE_VERIFIED"));
        assertTrue(script.contains("LATEST_EVIDENCE_LINEAGE_VERIFIED"));
        assertTrue(script.contains("current audited recommendation identity is missing"));
        assertFalse(script.contains("TRANSACTION_ALREADY_COMPLETE\" -or"));
        assertFalse(script.contains("TRANSACTION_PENDING_DO_NOT_DUPLICATE\" -or"));
    }

    @Test
    void myTeamRendersCurrentDropWithoutRankingOrWriteSemantics() throws Exception {
        String script = script();
        assertTrue(script.contains("Current governed DROP"));
        assertTrue(script.contains("Already-audited current DROP &middot; this marker is not a roster rank or lineup recommendation."));
        assertTrue(script.contains("$player.SleeperId -ceq $current.SleeperId"));
        assertTrue(script.contains("Current DROP Sleeper ID:"));
        assertTrue(script.contains("ConvertTo-TeamHtml -RosterContext $rosterContext -Summary $summary"));
        assertTrue(script.contains("select a new drop"));
        assertTrue(script.contains("run BF-641"));
        assertFalse(script.contains("Sort-Object"));
        assertFalse(script.contains("lineup optimization"));
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
        throw new IOException("BF-651 test could not locate scripts/butler-dashboard.ps1");
    }
}
