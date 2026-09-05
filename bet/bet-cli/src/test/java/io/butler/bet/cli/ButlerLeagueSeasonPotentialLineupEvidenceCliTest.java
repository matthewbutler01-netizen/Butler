package io.butler.bet.cli;

import io.butler.bet.intelligence.CoveredProductionScoringPolicy;
import io.butler.bet.intelligence.LeagueSeasonPotentialLineupEvidenceAnalyzer;
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

class ButlerLeagueSeasonPotentialLineupEvidenceCliTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
    private static final URI SOURCE = URI.create("https://example.test/stats_player_week_2026.csv");

    @Test
    void parsesAndRoutesExactCommandShape() {
        var args = new String[]{"league", "season-potential-lineup-evidence", "l1", "2026"};
        var options = ButlerLeagueSeasonPotentialLineupEvidenceCli.parse(args);

        assertEquals("l1", options.leagueId());
        assertEquals(2026, options.season());
        assertEquals(ButlerCommandRouter.Route.LEAGUE_SEASON_POTENTIAL_LINEUP_EVIDENCE,
            ButlerCommandRouter.route(args));
    }

    @Test
    void rejectsMalformedArguments() {
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueSeasonPotentialLineupEvidenceCli.parse(
            new String[]{"league", "season-potential-lineup-evidence", "l1", "bad"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueSeasonPotentialLineupEvidenceCli.parse(
            new String[]{"league", "season-potential-lineup-evidence", "l1"}));
    }

    @Test
    void printsTeamNameOrderSeparateDenominatorsBlockedReasonsAndNoRankingBoundary() {
        var alphaSeason = seasonEvidence("t-alpha", new BigDecimal("4.0"), true);
        var betaSeason = seasonEvidence("t-beta", new BigDecimal("20.0"), false);
        var report = new LeagueSeasonPotentialLineupEvidenceAnalyzer.LeagueEvidenceReport(
            LeagueSeasonPotentialLineupEvidenceAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE,
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WEEK_UNIVERSE,
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.POLICY_ID,
            "l1", "League", 2026,
            List.of(
                new LeagueSeasonPotentialLineupEvidenceAnalyzer.TeamEvidence(
                    "t-alpha", "Alpha Team", alphaSeason),
                new LeagueSeasonPotentialLineupEvidenceAnalyzer.TeamEvidence(
                    "t-beta", "Beta Team", betaSeason)));

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerLeagueSeasonPotentialLineupEvidenceCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        int alphaIndex = output.indexOf("Alpha Team [t-alpha]");
        int betaIndex = output.indexOf("Beta Team [t-beta]");
        assertTrue(alphaIndex >= 0 && betaIndex > alphaIndex);
        assertTrue(output.contains("Team order: repository team-name order; never score-ranked."));
        assertTrue(output.contains("qualifying total potential points: 4"));
        assertTrue(output.contains("qualifying total potential points: 20"));
        assertTrue(output.contains("aggregate denominator: 1 qualifying complete observed week(s)"));
        assertTrue(output.contains("week 2 BLOCKED"));
        assertTrue(output.contains("blocker: missing exact week evidence"));
        assertTrue(output.contains("teams are not ranked"));
        assertTrue(output.contains("no cross-team points aggregate or comparison is computed"));
        assertTrue(output.contains("differing team coverage denominators remain separate"));
    }

    private static LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.SeasonEvidenceReport seasonEvidence(
        String teamId, BigDecimal points, boolean includeBlockedWeek) {
        var qualifying = qualifyingWeek(teamId, 1, points);
        List<LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekEvidence> weeks;
        LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.SeasonAggregate aggregate;
        if (includeBlockedWeek) {
            var blocked = blockedWeek(teamId, 2);
            weeks = List.of(qualifying, blocked);
            aggregate = new LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.SeasonAggregate(
                2, 1, 0, 1, Optional.of(points), Optional.of(points));
        } else {
            weeks = List.of(qualifying);
            aggregate = new LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.SeasonAggregate(
                1, 0, 0, 1, Optional.of(points), Optional.of(points));
        }
        return new LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.SeasonEvidenceReport(
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE,
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WEEK_UNIVERSE,
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.AVERAGE_POLICY,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupAnalyzer.POLICY_ID,
            "l1", teamId, 2026, weeks, aggregate);
    }

    private static LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekEvidence qualifyingWeek(
        String teamId, int week, BigDecimal points) {
        var coverage = new LeagueTeamWeekPotentialLineupCoverageAnalyzer.CoverageReport(
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE,
            "l1", teamId, 2026, week,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.CoverageState.READY,
            AS_OF, AS_OF, AS_OF, SOURCE, List.of(), List.of());
        var lineup = new OptimalLegalLineupSolver.LineupResult(
            OptimalLegalLineupSolver.POLICY_ID,
            LineupSlotEligibilityPolicy.POLICY_ID,
            1, 1, points,
            List.of(new OptimalLegalLineupSolver.Assignment(0, "QB", "p-" + teamId, points)));
        var potential = new LeagueTeamWeekPotentialLineupAnalyzer.PotentialLineupReport(
            LeagueTeamWeekPotentialLineupAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE,
            CoveredProductionScoringPolicy.POLICY_ID,
            OptimalLegalLineupSolver.POLICY_ID,
            LineupSlotEligibilityPolicy.POLICY_ID,
            "l1", teamId, 2026, week,
            AS_OF, AS_OF, AS_OF, SOURCE, List.of(), lineup);
        return new LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekEvidence(
            week,
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekState.QUALIFYING_COMPLETE,
            AS_OF, coverage, potential, List.of());
    }

    private static LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekEvidence blockedWeek(
        String teamId, int week) {
        String blocker = "missing exact week evidence";
        var coverage = new LeagueTeamWeekPotentialLineupCoverageAnalyzer.CoverageReport(
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE,
            "l1", teamId, 2026, week,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.CoverageState.BLOCKED,
            AS_OF, AS_OF, null, null, List.of(), List.of(blocker));
        return new LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekEvidence(
            week,
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekState.BLOCKED,
            AS_OF, coverage, null, List.of(blocker));
    }
}
