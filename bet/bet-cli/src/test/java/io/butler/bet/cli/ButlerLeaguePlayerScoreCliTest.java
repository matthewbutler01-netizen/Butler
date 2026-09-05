package io.butler.bet.cli;

import io.butler.bet.intelligence.CoveredProductionScoringPolicy;
import io.butler.bet.intelligence.LeaguePlayerSeasonScoringAnalyzer;
import io.butler.bet.intelligence.LeagueScoringCoverageAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeaguePlayerScoreCliTest {
    @Test
    void parsesExactCommandShape() {
        var options = ButlerLeaguePlayerScoreCli.parse(
            new String[]{"league", "player-score", "l1", "p1", "2026", "nflverse"});

        assertEquals("l1", options.leagueId());
        assertEquals("p1", options.playerId());
        assertEquals(2026, options.season());
        assertEquals("nflverse", options.source());
    }

    @Test
    void routesExactCommandShape() {
        assertEquals(ButlerCommandRouter.Route.LEAGUE_PLAYER_SCORE,
            ButlerCommandRouter.route(
                new String[]{"league", "player-score", "l1", "p1", "2026", "nflverse"}));
    }

    @Test
    void rejectsMalformedArguments() {
        assertThrows(IllegalArgumentException.class, () -> ButlerLeaguePlayerScoreCli.parse(
            new String[]{"league", "player-score", "l1", "p1", "bad", "nflverse"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeaguePlayerScoreCli.parse(
            new String[]{"league", "player-score", "l1", "p1", "2026"}));
    }

    @Test
    void printsProvenanceTotalAndComponents() {
        var component = new CoveredProductionScoringPolicy.ScoreComponent(
            "rec", "receptions", 8, new BigDecimal("1.0"), new BigDecimal("8.0"));
        var score = new CoveredProductionScoringPolicy.ScoreResult(
            CoveredProductionScoringPolicy.POLICY_ID,
            "prod-1", "p1", 2026, new BigDecimal("8.0"), List.of(component));
        var report = new LeaguePlayerSeasonScoringAnalyzer.ScoringReport(
            LeaguePlayerSeasonScoringAnalyzer.POLICY_ID,
            LeagueScoringCoverageAnalyzer.POLICY_ID,
            CoveredProductionScoringPolicy.POLICY_ID,
            "l1", "Test League", "p1", 2026, "nflverse",
            "prod-1", LocalDate.of(2027, 1, 15), score);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerLeaguePlayerScoreCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains("League player-season score"));
        assertTrue(output.contains("Production snapshot: prod-1 as-of=2027-01-15"));
        assertTrue(output.contains("Total fantasy points: 8"));
        assertTrue(output.contains("rec | 8 | 1 | 8"));
        assertTrue(output.contains("does not rank players or make recommendations"));
    }
}
