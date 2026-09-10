package io.butler.bet.cli;

import io.butler.bet.intelligence.FranchiseValueRankingAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ButlerAppTest {
    @Test
    void rendersFranchiseRankingAsCompactPrimaryAndDetailRows() {
        var team = new FranchiseValueRankingAnalyzer.FranchiseValue(
            3, "team-123", "Love: JT, Jeanty &Javonte",
            1234.50, 678.25, 1912.75, 25, 6,
            LocalDate.of(2026, 8, 30), LocalDate.of(2026, 9, 2));

        String output = capture(() -> ButlerApp.printFranchiseRankingTeam(team));
        String[] lines = output.strip().split("\\R");

        assertEquals(2, lines.length);
        assertEquals(
            "3. Love: JT, Jeanty &Javonte  total=1912.75  players=1234.50  picks=678.25", lines[0]);
        assertEquals(
            "  assets: players=25  picks=6  dates=2026-08-30 -> 2026-09-02  team-id=team-123", lines[1]);
        assertTrue(lines[0].length() <= 120);
    }

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

    private static String capture(Runnable runnable) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            runnable.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
