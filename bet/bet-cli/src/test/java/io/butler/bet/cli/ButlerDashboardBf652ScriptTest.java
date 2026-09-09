package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardBf652ScriptTest {

    @Test
    void pairReconcilesExistingGovernedAddAndDropByExactSleeperId() throws Exception {
        String script = script();
        assertTrue(script.contains("function Get-CurrentGovernedTransactionPairView"));
        assertTrue(script.contains("Get-CurrentGovernedAddView -Bundle $Bundle -Summary $Summary"));
        assertTrue(script.contains("Get-CurrentGovernedDropView -RosterContext $RosterContext -Summary $Summary"));
        assertTrue(script.contains("$addCurrent.Active -ne $dropCurrent.Active"));
        assertTrue(script.contains("current ADD/DROP lifecycle disagreement"));
        assertTrue(script.contains("$add.SleeperId -cne $addCurrent.SleeperId"));
        assertTrue(script.contains("$drop.SleeperId -cne $dropCurrent.SleeperId"));
        assertTrue(script.contains("paired ADD must resolve exactly once in BF-616 shortlist"));
        assertTrue(script.contains("paired DROP must resolve exactly once in BF-610 target roster"));
        assertFalse(script.contains("Resolve-PairByName"));
    }

    @Test
    void pairReconcilesBf623TargetAcrossBothActionSources() throws Exception {
        String script = script();
        assertTrue(script.contains("foreach ($field in @(\"SleeperLeagueId\", \"RosterId\", \"LeagueName\", \"DisplayName\", \"TeamName\", \"Role\"))"));
        assertTrue(script.contains("BF-616 and BF-610 BF-623 target identity disagreement"));
        assertTrue(script.contains("Target = $addCurrent.Target"));
    }

    @Test
    void actionPagesUseOnlyExistingReadOnlySourcesForPairing() throws Exception {
        String script = script();
        assertTrue(script.contains("ConvertTo-TeamHtml -RosterContext $rosterContext -Summary $summary -Bundle $waiverBundle"));
        assertTrue(script.contains("ConvertTo-WaiverHtml -Bundle $waiverBundle -Summary $summary -RosterContext $rosterContext"));
        assertTrue(script.contains("$waiverBundle = Invoke-ButlerReadOnlyWaiverBoard"));
        assertTrue(script.contains("$rosterContext = Invoke-ButlerReadOnlyRosterContext"));
        assertFalse(script.contains("sleeperLiveWaiverFinalRecommendationBundle"));
        assertFalse(script.contains("BF-618/BF-620 recompute"));
    }

    @Test
    void pairedTransactionContextIsTraceabilityNotRankingOrSelection() throws Exception {
        String script = script();
        assertTrue(script.contains("Paired audited ADD"));
        assertTrue(script.contains("Paired audited DROP"));
        assertTrue(script.contains("View paired ADD"));
        assertTrue(script.contains("View paired DROP"));
        assertTrue(script.contains("already-audited current transaction"));
        assertTrue(script.contains("not a roster rank or lineup recommendation"));
        assertTrue(script.contains("not a board rank or new selection"));
        assertFalse(script.contains("Sort-Object"));
    }

    @Test
    void nonActionableLifecycleDoesNotExposePairContext() throws Exception {
        String script = script();
        assertTrue(script.contains("if (-not $addCurrent.Active)"));
        assertTrue(script.contains("Active = $false"));
        assertTrue(script.contains("$state -ceq \"CURRENT_AND_ACTIONABLE\" -or $state -ceq \"CURRENT_REFRESH_RECOMMENDED\""));
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
        throw new IOException("BF-652 test could not locate scripts/butler-dashboard.ps1");
    }
}
