package io.butler.bet.cli;

import io.butler.bet.intelligence.CoveredProductionScoringPolicy;
import io.butler.bet.intelligence.LeagueTeamWeekLineupPointsGapEvidenceAnalyzer;
import io.butler.bet.intelligence.LeagueTeamWeekPotentialLineupAnalyzer;
import io.butler.bet.intelligence.LeagueTeamWeekPotentialLineupCoverageAnalyzer;
import io.butler.bet.intelligence.LeagueTeamWeekStartedLineupEvidenceAnalyzer;
import io.butler.bet.intelligence.LineupSlotEligibilityPolicy;
import io.butler.bet.intelligence.OptimalLegalLineupSolver;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueTeamWeekLineupPointsGapEvidenceCliTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);

    @Test
    void parsesExactCommandShape() {
        var options = ButlerLeagueTeamWeekLineupPointsGapEvidenceCli.parse(new String[]{
            "league", "team-week-lineup-points-gap-evidence", "l1", "t1", "2026", "3"});

        assertEquals("l1", options.leagueId());
        assertEquals("t1", options.teamId());
        assertEquals(2026, options.season());
        assertEquals(3, options.week());
    }

    @Test
    void rejectsMalformedArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueTeamWeekLineupPointsGapEvidenceCli.parse(new String[]{
                "league", "team-week-lineup-points-gap-evidence", "l1", "t1", "bad", "3"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueTeamWeekLineupPointsGapEvidenceCli.parse(new String[]{
                "league", "team-week-lineup-points-gap-evidence", "l1", "t1", "2026", "0"}));
    }

    @Test
    void rendersGapWithBothSourceScopesAndNonAttributionBoundary() {
        var report = new LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.LineupPointsGapReport(
            LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.POLICY_ID,
            LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.METRIC_SCOPE,
            LeagueTeamWeekPotentialLineupAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE,
            LeagueTeamWeekStartedLineupEvidenceAnalyzer.POLICY_ID,
            LeagueTeamWeekStartedLineupEvidenceAnalyzer.METRIC_SCOPE,
            CoveredProductionScoringPolicy.POLICY_ID,
            OptimalLegalLineupSolver.POLICY_ID,
            LineupSlotEligibilityPolicy.POLICY_ID,
            "l1",
            "t1",
            2026,
            3,
            AS_OF,
            AS_OF,
            AS_OF,
            URI.create("https://example.test/week.csv"),
            2,
            new BigDecimal("10.0"),
            new BigDecimal("16.0"),
            new BigDecimal("6.0"));

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerLeagueTeamWeekLineupPointsGapEvidenceCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains("Team-week lineup points-gap evidence"));
        assertTrue(output.contains("potential: " + LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE));
        assertTrue(output.contains("started: " + LeagueTeamWeekStartedLineupEvidenceAnalyzer.METRIC_SCOPE));
        assertTrue(output.contains("Recalculated started points: 10"));
        assertTrue(output.contains("Retrospective potential points: 16"));
        assertTrue(output.contains("Potential-minus-started points gap: 6"));
        assertTrue(output.contains("not reconstructed historical startability"));
        assertTrue(output.contains("not a manager-efficiency score, percentage, rank, tier, recommendation"));
        assertTrue(output.contains("intent, fault, or skill attribution"));
    }
}
