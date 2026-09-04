package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppTest {
    @Test
    void parsesMixedTradeAssetsAndKeepsBarePlayerCompatibility() {
        var parsed = ButlerApp.parseTradePackage(
            " player:p1, pick:d1, p2, PICK:d2 ", "side-a-assets");

        assertEquals(java.util.List.of("p1", "p2"), parsed.playerIds());
        assertEquals(java.util.List.of("d1", "d2"), parsed.draftPickIds());
    }

    @Test
    void rejectsBlankIdsAndUnknownAssetPrefixes() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerApp.parseTradePackage("pick:", "side-a-assets"));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerApp.parseTradePackage("future:d1", "side-a-assets"));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerApp.parseTradePackage("p1,", "side-a-assets"));
    }

    @Test
    void routesRecommendationCommandToGovernedCli() {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out));
            System.setErr(new PrintStream(err));
            ButlerApp.main(new String[]{"trade", "recommendation"});
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertTrue(out.toString().contains("butler trade recommendation <league-id> <season>"));
        assertTrue(err.toString().contains("trade recommendation requires league, season"));
    }
}
