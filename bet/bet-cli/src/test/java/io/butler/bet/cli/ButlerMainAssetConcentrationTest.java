package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerMainAssetConcentrationTest {
    @Test
    void recognizesEverySupportedAssetConcentrationArgumentForm() {
        assertTrue(ButlerMain.isSupportedLeagueAssetConcentration(
            new String[]{"league", "asset-concentration", "league-id"}));
        assertTrue(ButlerMain.isSupportedLeagueAssetConcentration(
            new String[]{"league", "asset-concentration", "league-id", "source"}));
        assertTrue(ButlerMain.isSupportedLeagueAssetConcentration(
            new String[]{"league", "asset-concentration", "league-id", "--minimum-as-of", "2026-09-01"}));
        assertTrue(ButlerMain.isSupportedLeagueAssetConcentration(
            new String[]{"league", "asset-concentration", "league-id", "source", "--minimum-as-of", "2026-09-01"}));

        assertFalse(ButlerMain.isSupportedLeagueAssetConcentration(
            new String[]{"league", "asset-concentration"}));
        assertFalse(ButlerMain.isSupportedLeagueAssetConcentration(
            new String[]{"league", "asset-concentration", "league-id", "--wrong-flag", "2026-09-01"}));
    }

    @Test
    void advertisesAssetConcentrationSyntax() {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            ButlerMain.printIntelligenceUsage();
        } finally {
            System.setOut(original);
        }
        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains(
            "butler league asset-concentration <league-id> [source] [--minimum-as-of YYYY-MM-DD]"));
    }
}
