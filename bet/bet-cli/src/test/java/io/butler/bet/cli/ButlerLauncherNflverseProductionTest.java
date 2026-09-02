package io.butler.bet.cli;

import io.butler.bet.intelligence.NflversePlayerSeasonProductionImporter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLauncherNflverseProductionTest {
    @Test
    void recognizesOnlyPreviewAndRefreshWithOneSeasonArgument() {
        assertTrue(ButlerLauncher.isSupportedNflverseProduction(
            new String[]{"nflverse", "production-preview", "2025"}));
        assertTrue(ButlerLauncher.isSupportedNflverseProduction(
            new String[]{"nflverse", "production-refresh", "2025"}));
        assertFalse(ButlerLauncher.isSupportedNflverseProduction(
            new String[]{"nflverse", "production-preview"}));
        assertFalse(ButlerLauncher.isSupportedNflverseProduction(
            new String[]{"nflverse", "production-refresh", "2025", "extra"}));
    }

    @Test
    void rendersPreviewAsNonMutatingAndShowsMappingDiagnostics() {
        var result = new NflversePlayerSeasonProductionImporter.ImportResult(
            2025, LocalDate.of(2026, 1, 10), false, 500, 500, 12000, 450,
            300, 280, 20, 0,
            List.of(new NflversePlayerSeasonProductionImporter.UnmatchedPlayer("p1", "1001", "Missing Player")));

        String output = capture(() -> ButlerLauncher.printNflverseProduction(result));

        assertTrue(output.contains("nflverse production preview"));
        assertTrue(output.contains("eligible=300  matched=280  unmatched=20"));
        assertTrue(output.contains("Production snapshots written: 0 (preview only)"));
        assertTrue(output.contains("Missing Player  sleeper=1001"));
    }

    @Test
    void advertisesBothProductionCommands() {
        String output = capture(ButlerLauncher::printNflverseProductionUsage);
        assertTrue(output.contains("butler nflverse production-preview <season>"));
        assertTrue(output.contains("butler nflverse production-refresh <season>"));
    }

    private static String capture(Runnable runnable) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer));
            runnable.run();
            return buffer.toString();
        } finally {
            System.setOut(original);
        }
    }
}
