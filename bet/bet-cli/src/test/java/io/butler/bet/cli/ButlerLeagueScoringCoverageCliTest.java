package io.butler.bet.cli;

import io.butler.bet.intelligence.LeagueScoringCoverageAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueScoringCoverageCliTest {
    @Test
    void printsCoverageAndUnsupportedRulesWithoutCalculatingPoints() throws Exception {
        var report = new LeagueScoringCoverageAnalyzer.CoverageReport(
            LeagueScoringCoverageAnalyzer.POLICY_ID,
            "league-1",
            "Test League",
            LeagueScoringCoverageAnalyzer.CoverageState.INCOMPLETE,
            List.of(
                new LeagueScoringCoverageAnalyzer.RuleCoverage(
                    "pass_td", 6.0, LeagueScoringCoverageAnalyzer.RuleState.SUPPORTED, "passingTouchdowns"),
                new LeagueScoringCoverageAnalyzer.RuleCoverage(
                    "bonus_rec_te", 0.5, LeagueScoringCoverageAnalyzer.RuleState.UNSUPPORTED_NONZERO, null),
                new LeagueScoringCoverageAnalyzer.RuleCoverage(
                    "unused_rule", 0.0, LeagueScoringCoverageAnalyzer.RuleState.ZERO_IGNORED, null)),
            1, 1, 1,
            "At least one nonzero scoring rule is unsupported.");

        String output = capture(() -> ButlerLeagueScoringCoverageCli.print(report));

        assertTrue(output.contains("League scoring coverage"));
        assertTrue(output.contains("Coverage: INCOMPLETE"));
        assertTrue(output.contains("Exact scoring eligible: false"));
        assertTrue(output.contains("supported-nonzero=1 ignored-zero=1 unsupported-nonzero=1"));
        assertTrue(output.contains("pass_td | 6 | SUPPORTED | passingTouchdowns"));
        assertTrue(output.contains("bonus_rec_te | 0.5 | UNSUPPORTED_NONZERO | -"));
        assertTrue(output.contains("does not calculate fantasy points or make player recommendations"));
    }

    @Test
    void exactCommandShapeRoutesToCoverageCli() {
        var options = ButlerLeagueScoringCoverageCli.parse(
            new String[]{"league", "scoring-coverage", "league-1"});
        assertEquals("league-1", options.leagueId());
        assertEquals(ButlerCommandRouter.Route.LEAGUE_SCORING_COVERAGE,
            ButlerCommandRouter.route(new String[]{"league", "scoring-coverage", "league-1"}));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueScoringCoverageCli.parse(new String[]{"league", "scoring-coverage"}));
        assertTrue(error.getMessage().contains("Usage: butler league scoring-coverage <league-id>"));
    }

    private static String capture(ThrowingRunnable runnable) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            runnable.run();
        } finally {
            System.setOut(original);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
