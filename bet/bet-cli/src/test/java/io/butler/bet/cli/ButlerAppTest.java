package io.butler.bet.cli;

import io.butler.bet.intelligence.FranchiseValueRankingAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

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
    void rendersFranchiseRankingsAsCompactRowsWithoutChangingInputOrder() {
        var teams = List.of(
            new FranchiseValueRankingAnalyzer.FranchiseValue(
                1,
                "11111111-1111-1111-1111-111111111111",
                "Alpha Franchise",
                1000.0,
                250.0,
                1250.0,
                20,
                6,
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1)),
            new FranchiseValueRankingAnalyzer.FranchiseValue(
                2,
                "22222222-2222-2222-2222-222222222222",
                "Beta Franchise",
                900.0,
                200.0,
                1100.0,
                18,
                5,
                LocalDate.of(2026, 8, 25),
                LocalDate.of(2026, 9, 1)));

        String output = capture(() -> ButlerApp.printFranchiseRankingTeams(teams));
        List<String> lines = output.lines().toList();

        assertEquals(List.of(
            "1. Alpha Franchise  total=1250.00  players=1000.00  picks=250.00",
            "  assets=20 players + 6 picks  dates=2026-09-01  team-id=11111111-1111-1111-1111-111111111111",
            "2. Beta Franchise  total=1100.00  players=900.00  picks=200.00",
            "  assets=18 players + 5 picks  dates=2026-08-25 to 2026-09-01  team-id=22222222-2222-2222-2222-222222222222"), lines);
        assertTrue(lines.stream().allMatch(line -> line.length() <= 120));
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
