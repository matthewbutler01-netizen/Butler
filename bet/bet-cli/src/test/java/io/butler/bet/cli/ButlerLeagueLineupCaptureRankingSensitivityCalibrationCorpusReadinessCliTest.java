package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessCliTest {
    @TempDir Path tempDir;

    @Test
    void parsesExactAndCaseInsensitiveCommand() {
        var options = ButlerLeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessCli.parse(new String[] {
            "LEAGUE", "LINEUP-CAPTURE-RANKING-SENSITIVITY-CALIBRATION-CORPUS-READINESS", "2024", "2026"
        });

        assertEquals(2024, options.startSeason());
        assertEquals(2026, options.endSeason());
    }

    @Test
    void rejectsReversedOrOutOfRangeHistoricalScope() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessCli.parse(new String[] {
                "league", "lineup-capture-ranking-sensitivity-calibration-corpus-readiness", "2027", "2026"
            }));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessCli.parse(new String[] {
                "league", "lineup-capture-ranking-sensitivity-calibration-corpus-readiness", "1998", "2026"
            }));
    }

    @Test
    void rendersFailedStructuralGatesWithoutCallingCorpusStatisticallyAdequate() throws Exception {
        Database database = new Database(tempDir.resolve("empty-readiness.db"));
        database.initialize();
        var report = new LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer(database)
            .analyze(2024, 2026);

        String output = capture(() ->
            ButlerLeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessCli.print(report));

        assertTrue(output.contains("Historical lineup-capture rank-sensitivity calibration corpus structural readiness"));
        assertTrue(output.contains("Readiness state: NOT_READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN"));
        assertTrue(output.contains("[FAIL] MULTIPLE_LEAGUE_IDENTITIES"));
        assertTrue(output.contains("[FAIL] MULTIPLE_SEASONS"));
        assertTrue(output.contains("[FAIL] MULTIPLE_AVAILABLE_LEAGUE_SEASONS"));
        assertTrue(output.contains("[FAIL] MULTIPLE_TEAM_COUNT_STRATA"));
        assertTrue(output.contains("[FAIL] MULTIPLE_PERTURBATION_DENOMINATORS"));
        assertTrue(output.contains("[FAIL] TEMPORAL_OUTCOME_VARIATION"));
        assertTrue(output.contains("correlated rows; not independent sample N"));
        assertTrue(output.contains("not statistical sample-size adequacy, calibration, confidence, probability"));
        assertTrue(output.contains("generates no candidate threshold"));
        assertTrue(output.contains("fits no threshold"));
        assertTrue(output.contains("creates no manager consistency/reliability/quality score"));
    }

    private static String capture(Runnable runnable) {
        PrintStream previous = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            runnable.run();
            return bytes.toString();
        } finally {
            System.setOut(previous);
        }
    }
}
