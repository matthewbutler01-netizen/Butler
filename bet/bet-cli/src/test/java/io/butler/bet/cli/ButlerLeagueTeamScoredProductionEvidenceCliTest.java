package io.butler.bet.cli;

import io.butler.bet.intelligence.CoveredProductionScoringPolicy;
import io.butler.bet.intelligence.LeagueScoredProductionEvidenceAnalyzer;
import io.butler.bet.intelligence.LeagueScoringCoverageAnalyzer;
import io.butler.bet.intelligence.LeagueTeamScoredProductionEvidenceAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueTeamScoredProductionEvidenceCliTest {
    @Test
    void parsesAndRoutesExactCommandShape() {
        var args = new String[]{"league", "team-scored-production-evidence", "l1", "2026", "nflverse"};
        var options = ButlerLeagueTeamScoredProductionEvidenceCli.parse(args);

        assertEquals("l1", options.leagueId());
        assertEquals(2026, options.season());
        assertEquals("nflverse", options.source());
        assertEquals(ButlerCommandRouter.Route.LEAGUE_TEAM_SCORED_PRODUCTION_EVIDENCE,
            ButlerCommandRouter.route(args));
    }

    @Test
    void rejectsMalformedArguments() {
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueTeamScoredProductionEvidenceCli.parse(
            new String[]{"league", "team-scored-production-evidence", "l1", "bad", "nflverse"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueTeamScoredProductionEvidenceCli.parse(
            new String[]{"league", "team-scored-production-evidence", "l1", "2026"}));
    }

    @Test
    void printsCoverageAndPreservesNonRankedTeamOrder() {
        var alpha = new LeagueTeamScoredProductionEvidenceAnalyzer.TeamEvidence(
            "t-alpha", "Alpha Team", 2, 1, new BigDecimal("5.0"));
        var beta = new LeagueTeamScoredProductionEvidenceAnalyzer.TeamEvidence(
            "t-beta", "Beta Team", 1, 1, new BigDecimal("20.0"));
        var report = new LeagueTeamScoredProductionEvidenceAnalyzer.TeamEvidenceReport(
            LeagueTeamScoredProductionEvidenceAnalyzer.POLICY_ID,
            LeagueScoredProductionEvidenceAnalyzer.POLICY_ID,
            LeagueScoringCoverageAnalyzer.POLICY_ID,
            CoveredProductionScoringPolicy.POLICY_ID,
            "l1", "Test League", 2026, "nflverse", List.of(alpha, beta));

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerLeagueTeamScoredProductionEvidenceCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        int alphaIndex = output.indexOf("Alpha Team [t-alpha] | 5 | 1/2 (50.0%) | false");
        int betaIndex = output.indexOf("Beta Team [t-beta] | 20 | 1/1 (100.0%) | true");
        assertTrue(alphaIndex >= 0 && betaIndex > alphaIndex);
        assertTrue(output.contains("teams are not score-ranked"));
        assertTrue(output.contains("not lineup strength or a recommendation"));
    }
}
