package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditCliTest {
    @TempDir Path tempDir;

    @Test
    void parsesExactAndCaseInsensitiveCommand() {
        var exact = ButlerLeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditCli.parse(new String[] {
            "league", "lineup-capture-ranking-sensitivity-candidate-cross-fold-support-audit", "2024", "2026"
        });
        assertEquals(2024, exact.startSeason());
        assertEquals(2026, exact.endSeason());

        var mixedCase = ButlerLeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditCli.parse(new String[] {
            "LEAGUE", "LINEUP-CAPTURE-RANKING-SENSITIVITY-CANDIDATE-CROSS-FOLD-SUPPORT-AUDIT", "2025", "2025"
        });
        assertEquals(2025, mixedCase.startSeason());
        assertEquals(2025, mixedCase.endSeason());
    }

    @Test
    void rejectsInvalidSeasonRangeAndWrongCommand() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditCli.parse(new String[] {
                "league", "lineup-capture-ranking-sensitivity-candidate-cross-fold-support-audit", "2027", "2026"
            }));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditCli.parse(new String[] {
                "league", "wrong", "2025", "2026"
            }));
    }

    @Test
    void unavailableAuditPublishesNoCandidateRowsAndPreservesStopBoundary() throws Exception {
        Database database = new Database(tempDir.resolve("empty.db"));
        database.initialize();
        var report = new LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer(database)
            .analyze(2025, 2026);

        String output = capture(() ->
            ButlerLeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditCli.print(report));

        assertTrue(output.contains("Audit state: UNAVAILABLE_CANDIDATE_STUDY"));
        assertTrue(output.contains("No candidate cross-fold support evidence is published"));
        assertTrue(output.contains("Support states are evidence-breadth labels, not confidence"));
        assertTrue(output.contains("does not normalize those totals into a scalar score"));
        assertTrue(output.contains("rank candidates by apparent performance"));
        assertTrue(output.contains("select or break ties among candidates"));
        assertTrue(output.contains("fit/refine a threshold"));
        assertTrue(output.contains("score manager consistency/quality"));
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
