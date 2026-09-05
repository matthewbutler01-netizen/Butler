package io.butler.bet.cli;

import io.butler.bet.intelligence.CoveredProductionScoringPolicy;
import io.butler.bet.intelligence.LeagueScoredProductionEvidenceAnalyzer;
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

class ButlerLeagueScoredProductionEvidenceCliTest {
    @Test
    void parsesAndRoutesExactCommandShape() {
        var args = new String[]{"league", "scored-production-evidence", "l1", "2026", "nflverse"};
        var options = ButlerLeagueScoredProductionEvidenceCli.parse(args);

        assertEquals("l1", options.leagueId());
        assertEquals(2026, options.season());
        assertEquals("nflverse", options.source());
        assertEquals(ButlerCommandRouter.Route.LEAGUE_SCORED_PRODUCTION_EVIDENCE, ButlerCommandRouter.route(args));
    }

    @Test
    void rejectsMalformedArguments() {
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueScoredProductionEvidenceCli.parse(
            new String[]{"league", "scored-production-evidence", "l1", "bad", "nflverse"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueScoredProductionEvidenceCli.parse(
            new String[]{"league", "scored-production-evidence", "l1", "2026"}));
    }

    @Test
    void printsRosterOrderCoverageAndUnavailableEvidenceWithoutRanking() {
        var low = new LeagueScoredProductionEvidenceAnalyzer.PlayerEvidence(
            "t1", "Alpha", "01", "p-low", "Low Player", "WR", true,
            new BigDecimal("2.0"), "prod-low", LocalDate.of(2027, 1, 15), null);
        var high = new LeagueScoredProductionEvidenceAnalyzer.PlayerEvidence(
            "t1", "Alpha", "02", "p-high", "High Player", "WR", true,
            new BigDecimal("10.0"), "prod-high", LocalDate.of(2027, 1, 15), null);
        var missing = new LeagueScoredProductionEvidenceAnalyzer.PlayerEvidence(
            "t1", "Alpha", "03", "p-missing", "Missing Player", "WR", false,
            null, null, null, "No persisted production for requested season/source");
        var report = new LeagueScoredProductionEvidenceAnalyzer.EvidenceReport(
            LeagueScoredProductionEvidenceAnalyzer.POLICY_ID,
            LeagueScoringCoverageAnalyzer.POLICY_ID,
            CoveredProductionScoringPolicy.POLICY_ID,
            "l1", "Test League", 2026, "nflverse", List.of(low, high, missing), 2);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerLeagueScoredProductionEvidenceCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains("Coverage: 2/3 (66.7%) complete=false"));
        int lowIndex = output.indexOf("Low Player [p-low]");
        int highIndex = output.indexOf("High Player [p-high]");
        assertTrue(lowIndex >= 0 && highIndex > lowIndex);
        assertTrue(output.contains("Low Player [p-low] | WR | 2 | prod-low as-of=2027-01-15"));
        assertTrue(output.contains("Missing Player [p-missing] | WR | unavailable | No persisted production"));
        assertTrue(output.contains("players are not sorted by score"));
    }
}
