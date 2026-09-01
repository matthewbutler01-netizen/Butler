package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamStrengthAnalyzerTest {
    @Test
    void quarterbackAndStarterDepthIncreaseStrengthScore() {
        double strong = TeamStrengthAnalyzer.score(
            Map.of("QB", 2, "RB", 4, "WR", 5, "TE", 2),
            Map.of("STARTER", 9, "BENCH", 4));
        double thin = TeamStrengthAnalyzer.score(
            Map.of("QB", 1, "RB", 2, "WR", 3, "TE", 1),
            Map.of("STARTER", 6, "BENCH", 3));
        assertTrue(strong > thin);
    }

    @Test
    void startersAreWorthMoreThanBenchOrReserveSlots() {
        double starter = TeamStrengthAnalyzer.score(Map.of(), Map.of("STARTER", 1));
        double bench = TeamStrengthAnalyzer.score(Map.of(), Map.of("BENCH", 1));
        double reserve = TeamStrengthAnalyzer.score(Map.of(), Map.of("RESERVE", 1));
        assertTrue(starter > bench);
        assertTrue(bench > reserve);
    }
}
