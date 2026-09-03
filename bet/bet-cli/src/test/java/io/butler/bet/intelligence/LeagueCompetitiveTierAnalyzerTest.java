package io.butler.bet.intelligence;

import io.butler.bet.domain.TeamSeasonPerformance;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueCompetitiveTierAnalyzerTest {
    @Test
    void assignsLeagueRelativeOuterQuartilesAfterFourGames() {
        var performance = report(12, 4, false);

        var result = new LeagueCompetitiveTierAnalyzer().analyze(performance);

        assertTrue(result.available());
        assertEquals(LeagueCompetitiveTierPolicy.POLICY_ID, result.policyId());
        assertEquals(3, count(result, LeagueCompetitiveTierPolicy.Tier.FRONT_TIER));
        assertEquals(6, count(result, LeagueCompetitiveTierPolicy.Tier.MIDDLE_TIER));
        assertEquals(3, count(result, LeagueCompetitiveTierPolicy.Tier.BACK_TIER));
    }

    @Test
    void firstThreeGamesRemainInsufficientForEntireRelativeRanking() {
        var performance = report(12, 3, false);

        var result = new LeagueCompetitiveTierAnalyzer().analyze(performance);

        assertFalse(result.available());
        assertEquals(12, count(result, LeagueCompetitiveTierPolicy.Tier.INSUFFICIENT_EVIDENCE));
    }

    @Test
    void incompleteLeagueCoverageFailsClosed() {
        var performance = report(12, 4, true);

        var result = new LeagueCompetitiveTierAnalyzer().analyze(performance);

        assertFalse(result.available());
        assertTrue(result.unavailableReason().contains("coverage"));
        assertEquals(12, count(result, LeagueCompetitiveTierPolicy.Tier.INSUFFICIENT_EVIDENCE));
    }

    @Test
    void winPercentageRanksBeforePointsAndDifferential() {
        var teams = List.of(
            evidence("a", "A", snapshot("a", 3, 1, 0, 300, 200)),
            evidence("b", "B", snapshot("b", 2, 2, 0, 1000, 1)),
            evidence("c", "C", snapshot("c", 2, 2, 0, 500, 100)),
            evidence("d", "D", snapshot("d", 1, 3, 0, 400, 100))
        );
        var performance = new LeaguePerformanceEvidenceAnalyzer.PerformanceReport("l", 2026, "sleeper", teams);

        var result = new LeagueCompetitiveTierAnalyzer().analyze(performance);

        assertEquals(LeagueCompetitiveTierPolicy.Tier.FRONT_TIER, tier(result, "a"));
        assertEquals(LeagueCompetitiveTierPolicy.Tier.BACK_TIER, tier(result, "d"));
        assertEquals(LeagueCompetitiveTierPolicy.Tier.MIDDLE_TIER, tier(result, "b"));
    }

    @Test
    void identicalBoundaryMetricsDoNotUseTeamIdentityAsHiddenTiebreaker() {
        var teams = List.of(
            evidence("a", "A", snapshot("a", 3, 1, 0, 400, 300)),
            evidence("b", "B", snapshot("b", 3, 1, 0, 400, 300)),
            evidence("c", "C", snapshot("c", 1, 3, 0, 200, 300)),
            evidence("d", "D", snapshot("d", 1, 3, 0, 200, 300))
        );
        var result = new LeagueCompetitiveTierAnalyzer().analyze(
            new LeaguePerformanceEvidenceAnalyzer.PerformanceReport("l", 2026, "sleeper", teams));

        assertEquals(2, count(result, LeagueCompetitiveTierPolicy.Tier.FRONT_TIER));
        assertEquals(2, count(result, LeagueCompetitiveTierPolicy.Tier.BACK_TIER));
    }

    @Test
    void outerTierSizeNeverExceedsTwentyFivePercentByRoundingUp() {
        assertEquals(3, LeagueCompetitiveTierPolicy.outerTierSize(12));
        assertEquals(2, LeagueCompetitiveTierPolicy.outerTierSize(10));
        assertEquals(1, LeagueCompetitiveTierPolicy.outerTierSize(7));
        assertEquals(1, LeagueCompetitiveTierPolicy.outerTierSize(4));
    }

    private static LeaguePerformanceEvidenceAnalyzer.PerformanceReport report(int teamCount, int games, boolean missingLast) {
        List<LeaguePerformanceEvidenceAnalyzer.TeamPerformanceEvidence> teams = new ArrayList<>();
        for (int i = 0; i < teamCount; i++) {
            TeamSeasonPerformance snapshot = missingLast && i == teamCount - 1 ? null
                : snapshot("t" + i, Math.max(0, games - i % (games + 1)), Math.min(games, i % (games + 1)), 0,
                    500 - i * 10, 400 + i * 5);
            teams.add(evidence("t" + i, "Team " + i, snapshot));
        }
        return new LeaguePerformanceEvidenceAnalyzer.PerformanceReport("l", 2026, "sleeper", teams);
    }

    private static TeamSeasonPerformance snapshot(String teamId, int wins, int losses, int ties,
                                                  double pointsFor, double pointsAgainst) {
        return new TeamSeasonPerformance("l", teamId, 2026, wins, losses, ties, pointsFor, pointsAgainst,
            "sleeper", LocalDate.of(2026, 9, 3));
    }

    private static LeaguePerformanceEvidenceAnalyzer.TeamPerformanceEvidence evidence(
        String teamId, String teamName, TeamSeasonPerformance performance) {
        return new LeaguePerformanceEvidenceAnalyzer.TeamPerformanceEvidence(teamId, teamName, performance);
    }

    private static long count(LeagueCompetitiveTierAnalyzer.CompetitiveTierReport result,
                              LeagueCompetitiveTierPolicy.Tier tier) {
        return result.teams().stream().filter(team -> team.tier() == tier).count();
    }

    private static LeagueCompetitiveTierPolicy.Tier tier(LeagueCompetitiveTierAnalyzer.CompetitiveTierReport result,
                                                          String teamId) {
        return result.teams().stream().filter(team -> team.teamId().equals(teamId)).findFirst().orElseThrow().tier();
    }
}
