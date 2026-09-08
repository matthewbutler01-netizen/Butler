package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperLiveWaiverTargetRosterContextAudit;
import io.butler.bet.sleeper.SleeperLiveWaiverTargetRosterProductionComparabilityAudit;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperLiveWaiverTargetRosterProductionComparabilityAuditCliTest {
    @Test
    void rendersCoverageOnlyBoundary() {
        var target = new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
            "p1", "BENCH", null, null, "B1", "Example RB", "RB", "CHI", "EXACT_CANONICAL");
        var observation = new SleeperLiveWaiverTargetRosterProductionComparabilityAudit.ProductionObservation(
            "nflverse", LocalDate.of(2026, 2, 1), 17, 0, 0, 800, 7, 30, 220, 1);
        var coverage = new SleeperLiveWaiverTargetRosterProductionComparabilityAudit.TargetPlayerCoverage(
            target,
            SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState.PRIOR_PRODUCTION_PRESENT,
            List.of(observation));
        var report = new SleeperLiveWaiverTargetRosterProductionComparabilityAudit.AuditReport(
            SleeperLiveWaiverTargetRosterProductionComparabilityAudit.POLICY_ID,
            SleeperLiveWaiverTargetRosterContextAudit.POLICY_ID,
            "L", "M", "W", "S", 2026, "in_season", 1,
            "owner", "Owner", "Team", 1, "T", "Team",
            51, 42, 2025, 1, 0, 1, 0, 0, 1, 0,
            Map.of("nflverse", new SleeperLiveWaiverTargetRosterProductionComparabilityAudit.SourceCoverage(
                1, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1))),
            List.of(coverage));

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerSleeperLiveWaiverTargetRosterProductionComparabilityAuditCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains("Target prior-production PRESENT / MISSING: 1/0"));
        assertTrue(output.contains("Target-roster production comparability state: AUDITED_READ_ONLY"));
        assertTrue(output.contains("Missing 2025 production is an evidence gap"));
        assertTrue(output.contains("does not score roster needs"));
        assertTrue(output.contains("does not"));
        assertTrue(output.contains("rank players"));
    }
}
