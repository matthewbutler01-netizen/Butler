package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerCandidateThresholdStudyHelpTest {
    @Test
    void helpSurfacesCandidateStudyAndPreservesNoSelectionBoundary() {
        String output = capture(ButlerHelpLauncher::printGovernedLineupEvidenceUsage);

        assertTrue(output.contains(
            "butler league lineup-capture-ranking-sensitivity-candidate-threshold-study <start-season> <end-season>"));
        assertTrue(output.contains("leave-one-league-season-out descriptive evaluation"));
        assertTrue(output.contains("development-observed rational values"));
        assertTrue(output.contains("UNEVALUABLE_NO_HELD_OUT_SPLIT"));
        assertTrue(output.contains("NOT_GENERATED_IN_DEVELOPMENT_FOLD"));
        assertTrue(output.contains("never selects, optimizes, recommends, fits, refines, or publishes a production threshold"));
        assertTrue(output.contains("held-out cluster values never expand that fold's candidate set"));
        assertTrue(output.contains("no magnitude-frequency composite"));
        assertTrue(output.contains("no winner/optimal/recommended threshold is authorized"));
        assertTrue(output.contains("candidate-study rules"));
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
