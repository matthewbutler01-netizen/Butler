package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardBf649ScriptTest {

    @Test
    void summaryAndDashboardShareExactAuditedSnapshotLabel() throws Exception {
        String script = read("scripts/butler-dashboard.ps1");
        String cli = read("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.java");
        String label = "BF-631 audited BF-603 / BF-602 snapshot:";

        assertTrue(script.contains(label));
        assertTrue(cli.contains(label));
        assertTrue(cli.contains("report.auditedMarketSnapshotId()"));
        assertTrue(cli.contains("report.auditedWaiverSnapshotId()"));
    }

    @Test
    void currentAddMarkerRequiresExactGovernedCrossSurfaceReconciliation() throws Exception {
        String script = read("scripts/butler-dashboard.ps1");

        assertTrue(script.contains("function Get-CurrentGovernedAddView"));
        assertTrue(script.contains("function Get-Bf623TargetView"));
        assertTrue(script.contains("summary BF-623 target identity disagrees with waiver BF-623 target identity"));
        assertFalse(script.contains("\n        -or $bundleTarget."));
        assertTrue(script.contains("if ($bundleTarget.SleeperLeagueId -cne $summaryTarget.SleeperLeagueId)"));
        assertTrue(script.contains("if ($bundleTarget.RosterId -cne $summaryTarget.RosterId)"));
        assertTrue(script.contains("if ($bundleTarget.LeagueName -cne $summaryTarget.LeagueName)"));
        assertTrue(script.contains("if ($bundleTarget.DisplayName -cne $summaryTarget.DisplayName)"));
        assertTrue(script.contains("if ($bundleTarget.TeamName -cne $summaryTarget.TeamName)"));
        assertTrue(script.contains("if ($bundleTarget.Role -cne $summaryTarget.Role)"));
        assertTrue(script.contains("$state -ceq \"CURRENT_AND_ACTIONABLE\" -or $state -ceq \"CURRENT_REFRESH_RECOMMENDED\""));
        assertTrue(script.contains("$bf629 -cne \"LIVE_ACTIONABLE_VERIFIED\""));
        assertTrue(script.contains("$bf631 -cne \"LATEST_EVIDENCE_LINEAGE_VERIFIED\""));
        assertTrue(script.contains("audited BF-603/BF-602 lineage disagrees with BF-616 comparison bundle"));
        assertTrue(script.contains("$add.SleeperId -notmatch '^[0-9]+$'"));
        assertTrue(script.contains("Where-Object { $_.SleeperId -ceq $add.SleeperId }"));
        assertTrue(script.contains("$matches.Count -ne 1"));
        assertFalse(script.contains("candidate.Name -eq"));
        assertFalse(script.contains("Sort-Object"));
    }

    @Test
    void waiverBoardAndDetailRenderMarkerWithoutChangingRankingSemantics() throws Exception {
        String script = read("scripts/butler-dashboard.ps1");

        assertTrue(script.contains("Current governed ADD"));
        assertTrue(script.contains("Already-audited current ADD · this marker is not a board rank."));
        assertTrue(script.contains("it does not alter BF-616 order or rank the board"));
        assertTrue(script.contains("$current.Active -and $candidate.SleeperId -ceq $current.SleeperId"));
        assertTrue(script.contains("$current.Active -and $Candidate.SleeperId -ceq $current.SleeperId"));
        assertTrue(script.contains("ConvertTo-WaiverHtml -Bundle $waiverBundle -Summary $summary"));
        assertTrue(script.contains("ConvertTo-WaiverCandidateDetailHtml -Bundle $waiverBundle -Candidate $candidate -Summary $summary"));
        assertTrue(script.contains("Audited BF-603 / BF-602:"));
        assertTrue(script.contains("Bundle BF-603 / BF-602:"));
        assertTrue(script.contains("Newcomers remain nonnumeric"));
        assertTrue(script.contains("READ ONLY · NOT A RANKING."));
    }

    @Test
    void nonActionableLifecycleStatesReturnNoCurrentMarker() throws Exception {
        String script = read("scripts/butler-dashboard.ps1");

        assertTrue(script.contains("if (-not $active)"));
        assertTrue(script.contains("Active = $false"));
        assertTrue(script.contains("SleeperId = \"none\""));
        assertTrue(script.contains("TRANSACTION_ALREADY_COMPLETE"));
        assertTrue(script.contains("TRANSACTION_PENDING_DO_NOT_DUPLICATE"));
        assertTrue(script.contains("STALE_DO_NOT_ACT"));
    }

    private static String read(String relative) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) return Files.readString(candidate);
            current = current.getParent();
        }
        throw new IllegalStateException("BF-649 test could not locate " + relative);
    }
}
