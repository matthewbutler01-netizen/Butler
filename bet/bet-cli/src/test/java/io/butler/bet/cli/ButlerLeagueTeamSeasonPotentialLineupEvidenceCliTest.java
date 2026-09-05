package io.butler.bet.cli;

import io.butler.bet.intelligence.CoveredProductionScoringPolicy;
import io.butler.bet.intelligence.LeagueTeamSeasonPotentialLineupEvidenceAnalyzer;
import io.butler.bet.intelligence.LeagueTeamWeekPotentialLineupAnalyzer;
import io.butler.bet.intelligence.LeagueTeamWeekPotentialLineupCoverageAnalyzer;
import io.butler.bet.intelligence.LineupSlotEligibilityPolicy;
import io.butler.bet.intelligence.OptimalLegalLineupSolver;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueTeamSeasonPotentialLineupEvidenceCliTest {
    @Test
    void parsesAndRoutesExactCommandShape() {
        var args = new String[]{"league", "team-season-potential-lineup-evidence", "l1", "t1", "2026"};
        var options = ButlerLeagueTeamSeasonPotentialLineupEvidenceCli.parse(args);

        assertEquals("l1", options.leagueId());
        assertEquals("t1", options.teamId());
        assertEquals(2026, options.season());
        assertEquals(ButlerCommandRouter.Route.LEAGUE_TEAM_SEASON_POTENTIAL_LINEUP_EVIDENCE,
            ButlerCommandRouter.route(args));
    }

    @Test
    void rejectsMalformedArguments() {
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueTeamSeasonPotentialLineupEvidenceCli.parse(
            new String[]{"league", "team-season-potential-lineup-evidence", "l1", "t1", "bad"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueTeamSeasonPotentialLineupEvidenceCli.parse(
            new String[]{"league", "team-season-potential-lineup-evidence", "l1", "t1"}));
    }

    @Test
    void printsObservedWeekStatesExplicitDenominatorAndBoundary() {
        LocalDate asOf = LocalDate.of(2026, 9, 5);
        URI source = URI.create("https://example.test/stats_player_week_2026.csv");
        var readyCoverage = new LeagueTeamWeekPotentialLineupCoverageAnalyzer.CoverageReport(
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE,
            "l1", "t1", 2026, 1,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.CoverageState.READY,
            asOf, asOf, asOf, source, List.of(), List.of());
        var lineup = new OptimalLegalLineupSolver.LineupResult(
            OptimalLegalLineupSolver.POLICY_ID,
            LineupSlotEligibilityPolicy.POLICY_ID,
            1, 1, new BigDecimal("10.0"),
            List.of(new OptimalLegalLineupSolver.Assignment(0, "QB", "p1", new BigDecimal("10.0"))));
        var potential = new LeagueTeamWeekPotentialLineupAnalyzer.PotentialLineupReport(
            LeagueTeamWeekPotentialLineupAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE,
            CoveredProductionScoringPolicy.POLICY_ID,
            OptimalLegalLineupSolver.POLICY_ID,
            LineupSlotEligibilityPolicy.POLICY_ID,
            "l1", "t1", 2026, 1,
            asOf, asOf, asOf, source, List.of(), lineup);
        var qualifying = new LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekEvidence(
            1,
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekState.QUALIFYING_COMPLETE,
            asOf,
            readyCoverage,
            potential,
            List.of());

        String blocker = "No persisted nflverse week production coverage";
        var blockedCoverage = new LeagueTeamWeekPotentialLineupCoverageAnalyzer.CoverageReport(
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE,
            "l1", "t1", 2026, 2,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.CoverageState.BLOCKED,
            asOf, asOf, null, null, List.of(), List.of(blocker));
        var blocked = new LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekEvidence(
            2,
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekState.BLOCKED,
            asOf,
            blockedCoverage,
            null,
            List.of(blocker));

        var aggregate = new LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.SeasonAggregate(
            2, 1, 0, 1,
            Optional.of(new BigDecimal("10.0")),
            Optional.of(new BigDecimal("10.0")));
        var report = new LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.SeasonEvidenceReport(
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE,
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WEEK_UNIVERSE,
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.AVERAGE_POLICY,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupAnalyzer.POLICY_ID,
            "l1", "t1", 2026,
            List.of(qualifying, blocked), aggregate);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerLeagueTeamSeasonPotentialLineupEvidenceCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains(LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WEEK_UNIVERSE));
        assertTrue(output.contains("Week 1 | QUALIFYING_COMPLETE"));
        assertTrue(output.contains("potential points: 10"));
        assertTrue(output.contains("aggregate eligibility: included"));
        assertTrue(output.contains("Week 2 | BLOCKED"));
        assertTrue(output.contains("blocker: " + blocker));
        assertTrue(output.contains("qualifying complete weeks: 1"));
        assertTrue(output.contains("qualifying total potential points: 10"));
        assertTrue(output.contains("aggregate denominator: 1 qualifying complete observed week(s)"));
        assertTrue(output.contains("unobserved weeks are not treated as covered"));
        assertTrue(output.contains("not rankings, and not recommendations"));
    }
}
