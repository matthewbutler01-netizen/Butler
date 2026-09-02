package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerMainPositionalDepthTest {
    @Test
    void recognizesEverySupportedPositionalDepthArgumentForm() {
        assertTrue(ButlerMain.isSupportedLeaguePositionalDepth(new String[]{"league", "positional-depth", "league-id"}));
        assertTrue(ButlerMain.isSupportedLeaguePositionalDepth(new String[]{"league", "positional-depth", "league-id", "source"}));
        assertTrue(ButlerMain.isSupportedLeaguePositionalDepth(new String[]{"league", "positional-depth", "league-id", "--minimum-as-of", "2026-09-01"}));
        assertTrue(ButlerMain.isSupportedLeaguePositionalDepth(new String[]{"league", "positional-depth", "league-id", "source", "--minimum-as-of", "2026-09-01"}));
        assertFalse(ButlerMain.isSupportedLeaguePositionalDepth(new String[]{"league", "positional-depth"}));
        assertFalse(ButlerMain.isSupportedLeaguePositionalDepth(new String[]{"league", "positional-depth", "league-id", "--wrong-flag", "2026-09-01"}));
    }

    @Test
    void advertisesPositionalDepthSyntax() {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            ButlerMain.printIntelligenceUsage();
        } finally {
            System.setOut(original);
        }
        assertTrue(buffer.toString(StandardCharsets.UTF_8).contains(
            "butler league positional-depth <league-id> [source] [--minimum-as-of YYYY-MM-DD]"));
    }
}
