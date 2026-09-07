package io.butler.bet.cli;

import io.butler.bet.intelligence.CoveredProductionScoringPolicy;
import io.butler.bet.intelligence.HistoricalScoringLaneSelector;
import io.butler.bet.intelligence.LeagueTeamWeekLineupPointsGapEvidenceAnalyzer;
import io.butler.bet.intelligence.LeagueTeamWeekPotentialLineupAnalyzer;
import io.butler.bet.intelligence.LeagueTeamWeekPotentialLineupCoverageAnalyzer;
import io.butler.bet.intelligence.LeagueTeamWeekStartedLineupEvidenceAnalyzer;
import io.butler.bet.intelligence.LineupSlotEligibilityPolicy;
import io.butler.bet.intelligence.OptimalLegalLineupSolver;
import io.butler.bet.sleeper.SleeperProviderNativeSeasonScoringAudit;
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
    private static final LocalDate PROVIDER_AS_OF = LocalDate.of(2026, 9, 6);

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
    void rendersNflverseGapWithLaneAndNonAttributionBoundary() {
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

        String output = render(report);
        assertTrue(output.contains("Team-week lineup points-gap evidence"));
        assertTrue(output.contains("potential: " + LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE));
        assertTrue(output.contains("started: " + LeagueTeamWeekStartedLineupEvidenceAnalyzer.METRIC_SCOPE));
        assertTrue(output.contains("scoring lane selector: " + HistoricalScoringLaneSelector.POLICY_ID));
        assertTrue(output.contains("scoring lane: NFLVERSE_EXACT"));
        assertTrue(output.contains("production coverage as-of: " + AS_OF));
        assertTrue(output.contains("Started points: 10"));
        assertTrue(output.contains("Retrospective potential points: 16"));
        assertTrue(output.contains("Potential-minus-started points gap: 6"));
        assertTrue(output.contains("not reconstructed historical startability"));
        assertTrue(output.contains("not a manager-efficiency score, percentage, rank, tier, recommendation"));
        assertTrue(output.contains("intent, fault, or skill attribution"));
    }

    @Test
    void rendersProviderNativeGapWithoutMislabelingItAsProduction() {
        var report = new LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.LineupPointsGapReport(
            LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.POLICY_ID,
            LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.METRIC_SCOPE,
            LeagueTeamWeekPotentialLineupAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE,
            LeagueTeamWeekStartedLineupEvidenceAnalyzer.POLICY_ID,
            LeagueTeamWeekStartedLineupEvidenceAnalyzer.METRIC_SCOPE,
            HistoricalScoringLaneSelector.POLICY_ID,
            HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE,
            HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID,
            OptimalLegalLineupSolver.POLICY_ID,
            LineupSlotEligibilityPolicy.POLICY_ID,
            "l1",
            "t1",
            2026,
            3,
            AS_OF,
            AS_OF,
            null,
            null,
            PROVIDER_AS_OF,
            SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE,
            "provider-l1",
            2,
            new BigDecimal("10.0"),
            new BigDecimal("16.0"),
            new BigDecimal("6.0"));

        String output = render(report);
        assertTrue(output.contains("scoring lane: SLEEPER_PROVIDER_NATIVE"));
        assertTrue(output.contains("scoring: " + HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID));
        assertTrue(output.contains("provider points as-of: " + PROVIDER_AS_OF));
        assertTrue(output.contains("provider points source surface: "
            + SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE));
        assertTrue(output.contains("provider league id: provider-l1"));
        assertTrue(!output.contains("production coverage as-of:"));
        assertTrue(!output.contains("Recalculated started points:"));
        assertTrue(output.contains("Started points: 10"));
    }

    private static String render(LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.LineupPointsGapReport report) {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerLeagueTeamWeekLineupPointsGapEvidenceCli.print(report);
        } finally {
            System.setOut(original);
        }
        return bytes.toString();
    }
}
