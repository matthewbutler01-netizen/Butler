package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueLineupCaptureRankingSensitivityCandidateThresholdStudyCliTest {
    @TempDir Path tempDir;

    @Test
    void parsesExactAndCaseInsensitiveCommand() {
        var exact = ButlerLeagueLineupCaptureRankingSensitivityCandidateThresholdStudyCli.parse(new String[] {
            "league", "lineup-capture-ranking-sensitivity-candidate-threshold-study", "2024", "2026"
        });
        assertEquals(2024, exact.startSeason());
        assertEquals(2026, exact.endSeason());

        var mixedCase = ButlerLeagueLineupCaptureRankingSensitivityCandidateThresholdStudyCli.parse(new String[] {
            "LEAGUE", "LINEUP-CAPTURE-RANKING-SENSITIVITY-CANDIDATE-THRESHOLD-STUDY", "2025", "2025"
        });
        assertEquals(2025, mixedCase.startSeason());
        assertEquals(2025, mixedCase.endSeason());
    }

    @Test
    void rejectsInvalidSeasonRangeAndWrongCommand() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueLineupCaptureRankingSensitivityCandidateThresholdStudyCli.parse(new String[] {
                "league", "lineup-capture-ranking-sensitivity-candidate-threshold-study", "2027", "2026"
            }));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueLineupCaptureRankingSensitivityCandidateThresholdStudyCli.parse(new String[] {
                "league", "wrong", "2025", "2026"
            }));
    }

    @Test
    void unavailableStudyPrintsNoCandidateEvidenceAndPreservesBoundary() throws Exception {
        Database database = new Database(tempDir.resolve("empty.db"));
        database.initialize();
        var report = new LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer(database)
            .analyze(2025, 2026);

        String output = capture(() ->
            ButlerLeagueLineupCaptureRankingSensitivityCandidateThresholdStudyCli.print(report));

        assertTrue(output.contains("Study state: UNAVAILABLE_CORPUS_NOT_STRUCTURALLY_READY"));
        assertTrue(output.contains("No candidate fold evidence is published"));
        assertTrue(output.contains("does not select a best/optimal/recommended/production threshold"));
        assertTrue(output.contains("pool team-cutoff rows as independent N"));
        assertTrue(output.contains("estimate probability/confidence/significance"));
        assertTrue(output.contains("combine magnitude and frequency"));
        assertTrue(output.contains("adjust BF-500 ranks"));
        assertTrue(output.contains("manager consistency/reliability/quality"));
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
