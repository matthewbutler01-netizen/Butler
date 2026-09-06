package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerCandidateCrossFoldSupportAuditHelpTest {
    @Test
    void helpSurfacesSupportAuditAndPreservesPostCloseoutStopBoundary() {
        String output = capture(ButlerHelpLauncher::printGovernedLineupEvidenceUsage);

        assertTrue(output.contains(
            "butler league lineup-capture-ranking-sensitivity-candidate-cross-fold-support-audit <start-season> <end-season>"));
        assertTrue(output.contains("NO_EVALUABLE_FOLDS"));
        assertTrue(output.contains("SINGLE_EVALUABLE_FOLD"));
        assertTrue(output.contains("MULTI_FOLD_NARROW_SUPPORT"));
        assertTrue(output.contains("MULTI_FOLD_DIVERSE_SUPPORT"));
        assertTrue(output.contains("raw total absolute temporal rank displacement"));
        assertTrue(output.contains("each side's row count and full distributions"));
        assertTrue(output.contains("support states describe evidence breadth only, not confidence"));
        assertTrue(output.contains("not a win rate, probability, support score, or ranking input"));
        assertTrue(output.contains("may not reorder or tie-break candidates using support or displacement results"));
        assertTrue(output.contains("new governed methodology decision is required before any scalar objective"));
        assertTrue(output.contains("normalized candidate score, or candidate selection"));
        assertTrue(output.contains("candidate-support audit rules"));
        assertTrue(output.contains("BF-529 through BF-532 closeout"));
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
