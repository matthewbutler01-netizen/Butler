package io.butler.bet.cli;

import io.butler.bet.intelligence.CoveredProductionScoringPolicy;
import io.butler.bet.intelligence.HistoricalScoringLaneSelector;
import io.butler.bet.intelligence.LeagueTeamWeekPotentialLineupAnalyzer;
import io.butler.bet.intelligence.LeagueTeamWeekPotentialLineupCoverageAnalyzer;
import io.butler.bet.intelligence.LineupSlotEligibilityPolicy;
import io.butler.bet.intelligence.OptimalLegalLineupSolver;
import io.butler.bet.sleeper.SleeperHistoricalLineupEvidenceImporter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueTeamWeekPotentialLineupCliTest {
    @Test
    void parsesAndRoutesExactCommandShape() {
        var args = new String[]{"league", "team-week-potential-lineup", "l1", "t1", "2026", "3"};
        var options = ButlerLeagueTeamWeekPotentialLineupCli.parse(args);

        assertEquals("l1", options.leagueId());
        assertEquals("t1", options.teamId());
        assertEquals(2026, options.season());
        assertEquals(3, options.week());
        assertFalse(options.syncSleeper());
        assertEquals(ButlerCommandRouter.Route.LEAGUE_TEAM_WEEK_POTENTIAL_LINEUP,
            ButlerCommandRouter.route(args));
    }

    @Test
    void parsesOptionalSleeperHistoricalPrerequisiteSync() {
        var args = new String[]{
            "league", "team-week-potential-lineup", "l1", "t1", "2025", "1", "--sync-sleeper"};
        var options = ButlerLeagueTeamWeekPotentialLineupCli.parse(args);

        assertEquals(2025, options.season());
        assertEquals(1, options.week());
        assertTrue(options.syncSleeper());
        assertEquals(ButlerCommandRouter.Route.LEAGUE_TEAM_WEEK_POTENTIAL_LINEUP,
            ButlerCommandRouter.route(args));
    }

    @Test
    void rejectsMalformedArguments() {
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueTeamWeekPotentialLineupCli.parse(
            new String[]{"league", "team-week-potential-lineup", "l1", "t1", "bad", "3"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueTeamWeekPotentialLineupCli.parse(
            new String[]{"league", "team-week-potential-lineup", "l1", "t1", "2026", "0"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueTeamWeekPotentialLineupCli.parse(
            new String[]{"league", "team-week-potential-lineup", "l1", "t1", "2026"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueTeamWeekPotentialLineupCli.parse(
            new String[]{"league", "team-week-potential-lineup", "l1", "t1", "2026", "3", "--bad"}));
    }

    @Test
    void printsHistoricalSyncSummary() {
        var result = new SleeperHistoricalLineupEvidenceImporter.ImportResult(
            "l1", 2025, 1, "old-league", 1, 12, 187, 24,
            "sleeper", LocalDate.of(2026, 9, 6));
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerLeagueTeamWeekPotentialLineupCli.printSync(result);
        } finally {
            System.setOut(original);
        }
        String output = bytes.toString();
        assertTrue(output.contains("historical lineup prerequisites synchronized"));
        assertTrue(output.contains("Resolved Sleeper league: old-league"));
        assertTrue(output.contains("History hops: 1"));
        assertTrue(output.contains("Player identity/position observations: 187 new-player-mappings=24"));
        assertTrue(output.contains("not reconstructed historical eligibility"));
        assertTrue(output.contains("Team-week roster snapshots: 12 week=1"));
    }

    @Test
    void printsGovernedNflverseProvenancePlayerStatesAndSolverBoundary() {
        LocalDate asOf = LocalDate.of(2026, 9, 5);
        var observed = new LeagueTeamWeekPotentialLineupAnalyzer.PlayerScoreEvidence(
            "s1", "p1", asOf, List.of("QB"), HistoricalScoringLaneSelector.Lane.NFLVERSE_EXACT,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.OBSERVED,
            "prod-1", asOf, null, CoveredProductionScoringPolicy.POLICY_ID, new BigDecimal("18.5"));
        var zero = new LeagueTeamWeekPotentialLineupAnalyzer.PlayerScoreEvidence(
            "s2", "p2", asOf, List.of("WR"), HistoricalScoringLaneSelector.Lane.NFLVERSE_EXACT,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.IDENTITY_COVERED_ZERO,
            null, asOf, null, null, BigDecimal.ZERO);
        var lineup = lineup(new BigDecimal("18.5"));
        var report = new LeagueTeamWeekPotentialLineupAnalyzer.PotentialLineupReport(
            LeagueTeamWeekPotentialLineupAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE,
            HistoricalScoringLaneSelector.POLICY_ID,
            HistoricalScoringLaneSelector.Lane.NFLVERSE_EXACT,
            CoveredProductionScoringPolicy.POLICY_ID,
            OptimalLegalLineupSolver.POLICY_ID,
            LineupSlotEligibilityPolicy.POLICY_ID,
            "l1", "t1", 2026, 3,
            asOf, asOf, asOf,
            URI.create("https://example.test/stats_player_week_2026.csv"),
            null, null, null,
            List.of(observed, zero), lineup);

        String output = print(report);
        assertTrue(output.contains(LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE));
        assertTrue(output.contains("not reconstructed historical startability"));
        assertTrue(output.contains("scoring lane selection: " + HistoricalScoringLaneSelector.POLICY_ID));
        assertTrue(output.contains("selected scoring lane: NFLVERSE_EXACT"));
        assertTrue(output.contains("production source: https://example.test/stats_player_week_2026.csv"));
        assertTrue(output.contains("Sleeper s1 -> Butler p1"));
        assertTrue(output.contains("production state: OBSERVED"));
        assertTrue(output.contains("production id: prod-1"));
        assertTrue(output.contains("Sleeper s2 -> Butler p2"));
        assertTrue(output.contains("production state: IDENTITY_COVERED_ZERO"));
        assertTrue(output.contains("none (identity-covered zero)"));
        assertTrue(output.contains("#0 QB -> p1 | 18.5"));
        assertTrue(output.contains("#1 FLEX -> p2 | 0"));
        assertTrue(output.contains("Complete legal lineup: true"));
        assertTrue(output.contains("Total potential points: 18.5"));
        assertTrue(output.contains("not a ranking, and not a recommendation"));
    }

    @Test
    void printsProviderNativeProvenanceWithoutPretendingNflverseProductionExists() {
        LocalDate asOf = LocalDate.of(2026, 9, 6);
        var provider = new LeagueTeamWeekPotentialLineupAnalyzer.PlayerScoreEvidence(
            "s1", "p1", asOf, List.of("QB"), HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.NOT_EVALUATED,
            null, null, "evidence-1", HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID,
            new BigDecimal("18.5000"));
        var lineup = new OptimalLegalLineupSolver.LineupResult(
            OptimalLegalLineupSolver.POLICY_ID,
            LineupSlotEligibilityPolicy.POLICY_ID,
            1,
            1,
            new BigDecimal("18.5000"),
            List.of(new OptimalLegalLineupSolver.Assignment(0, "QB", "p1", new BigDecimal("18.5000"))));
        var report = new LeagueTeamWeekPotentialLineupAnalyzer.PotentialLineupReport(
            LeagueTeamWeekPotentialLineupAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE,
            HistoricalScoringLaneSelector.POLICY_ID,
            HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE,
            HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID,
            OptimalLegalLineupSolver.POLICY_ID,
            LineupSlotEligibilityPolicy.POLICY_ID,
            "l1", "t1", 2026, 3,
            asOf, asOf, null, null,
            asOf, "matchup.players_points", "provider-league",
            List.of(provider), lineup);

        String output = print(report);
        assertTrue(output.contains("selected scoring lane: SLEEPER_PROVIDER_NATIVE"));
        assertTrue(output.contains("provider points as-of: 2026-09-06"));
        assertTrue(output.contains("provider source surface: matchup.players_points"));
        assertTrue(output.contains("historical provider league: provider-league"));
        assertTrue(output.contains("provider points evidence id: evidence-1"));
        assertFalse(output.contains("production source:"));
        assertFalse(output.contains("identity-covered zero"));
    }

    private static OptimalLegalLineupSolver.LineupResult lineup(BigDecimal total) {
        return new OptimalLegalLineupSolver.LineupResult(
            OptimalLegalLineupSolver.POLICY_ID,
            LineupSlotEligibilityPolicy.POLICY_ID,
            2,
            2,
            total,
            List.of(
                new OptimalLegalLineupSolver.Assignment(0, "QB", "p1", total),
                new OptimalLegalLineupSolver.Assignment(1, "FLEX", "p2", BigDecimal.ZERO)));
    }

    private static String print(LeagueTeamWeekPotentialLineupAnalyzer.PotentialLineupReport report) {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerLeagueTeamWeekPotentialLineupCli.print(report);
        } finally {
            System.setOut(original);
        }
        return bytes.toString();
    }
}
