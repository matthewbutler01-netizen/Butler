package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperLiveWaiverCandidateRosterComparisonReadinessAudit;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperLiveWaiverCandidateRosterComparisonReadinessAuditCliTest {
    @Test
    void rendersMethodologyAuthorizationWithoutRecommendationSemantics() {
        Map<String, SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidatePositionCoverage> candidates = new LinkedHashMap<>();
        candidates.put("QB", new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidatePositionCoverage(2, 0));
        candidates.put("RB", new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidatePositionCoverage(10, 3));
        candidates.put("WR", new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidatePositionCoverage(18, 6));
        candidates.put("TE", new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidatePositionCoverage(2, 1));

        Map<String, SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterPositionCoverage> roster = new LinkedHashMap<>();
        roster.put("QB", new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterPositionCoverage(2, 2, 0, 2, 0, 0, 0));
        roster.put("RB", new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterPositionCoverage(5, 4, 1, 3, 2, 0, 0));
        roster.put("WR", new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterPositionCoverage(7, 6, 1, 3, 3, 1, 0));
        roster.put("TE", new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterPositionCoverage(1, 1, 0, 1, 0, 0, 0));

        var report = new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.AuditReport(
            SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.POLICY_ID,
            "L", "M", "W", "S", 2026, "in_season", 1,
            "owner", 1,
            51, 5, 4, 32, 10, 42,
            15, 9, 5, 1, 0,
            13, 2,
            candidates,
            roster,
            List.of(
                new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.MissingRosterProduction(
                    "13281", "Jordyn Tyson", "WR", "RESERVE"),
                new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.MissingRosterProduction(
                    "13288", "Nicholas Singleton", "RB", "BENCH")),
            SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.AuthorizationState.READY_FOR_COMPARISON_METHODOLOGY);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerSleeperLiveWaiverCandidateRosterComparisonReadinessAuditCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains("BF-609 candidates total / reviewable: 51/42"));
        assertTrue(output.contains("Target roster prior-production PRESENT / MISSING: 13/2"));
        assertTrue(output.contains("Comparison-readiness state: READY_FOR_COMPARISON_METHODOLOGY"));
        assertTrue(output.contains("does not mean any waiver candidate is better than any roster player"));
        assertTrue(output.contains("does not score players or roster needs"));
    }
}
