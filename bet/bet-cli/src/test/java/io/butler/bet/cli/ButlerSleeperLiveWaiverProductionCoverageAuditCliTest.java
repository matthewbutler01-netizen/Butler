package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperLiveWaiverProductionCoverageAudit;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperLiveWaiverProductionCoverageAuditCliTest {
    @Test
    void rendersReadOnlyCoveragePartitionAndBoundary() {
        var market = new SleeperLiveWaiverProductionCoverageAudit.MarketCandidate(
            "A", "Alpha", "WR", "MIN", "Active", 100, 20, 80, "BOTH");
        var production = new SleeperLiveWaiverProductionCoverageAudit.ProductionObservation(
            "NFLVERSE", LocalDate.parse("2025-09-03"), 17,
            0, 0, 0, 0, 120, 1100, 8);
        var sources = new LinkedHashMap<String, SleeperLiveWaiverProductionCoverageAudit.SourceCoverage>();
        sources.put("NFLVERSE", new SleeperLiveWaiverProductionCoverageAudit.SourceCoverage(
            1, LocalDate.parse("2025-09-03"), LocalDate.parse("2025-09-03")));
        var report = new SleeperLiveWaiverProductionCoverageAudit.AuditReport(
            SleeperLiveWaiverProductionCoverageAudit.POLICY_ID,
            "L", "M", "2026-09-08T01:42:00Z", 2025,
            1, 0, 0, 1, sources,
            List.of(new SleeperLiveWaiverProductionCoverageAudit.CandidateCoverage(
                market, "PA",
                SleeperLiveWaiverProductionCoverageAudit.CoverageState.MAPPED_WITH_2025_PRODUCTION,
                List.of(production))));

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerSleeperLiveWaiverProductionCoverageAuditCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains("Market-active candidates: 1"));
        assertTrue(output.contains("Coverage UNMAPPED_CANONICAL / MAPPED_NO_2025_PRODUCTION / MAPPED_WITH_2025_PRODUCTION: 0/0/1"));
        assertTrue(output.contains("production source=NFLVERSE as_of=2025-09-03"));
        assertTrue(output.contains("Coverage audit state: AUDITED_READ_ONLY"));
        assertTrue(output.contains("does not rank candidates"));
    }
}
