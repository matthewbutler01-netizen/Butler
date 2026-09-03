package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeAssetPositionalContextAnalyzerTest {
    @Test
    void requiresEveryCorePosition() {
        var identity = new TradeAssetStrategicContextAnalyzer.TeamIdentity("t1", "Team 1");
        assertThrows(IllegalArgumentException.class, () -> new TradeAssetPositionalContextAnalyzer.TeamPositionalContext(
            identity, Map.of("QB", team("QB", "t1", "Team 1"))));
    }

    @Test
    void rejectsPositionEvidenceFromDifferentTeam() {
        var identity = new TradeAssetStrategicContextAnalyzer.TeamIdentity("t1", "Team 1");
        Map<String, LeaguePositionalPressureAnalyzer.TeamPositionPressure> positions = new LinkedHashMap<>();
        positions.put("QB", team("QB", "t1", "Team 1"));
        positions.put("RB", team("RB", "t1", "Team 1"));
        positions.put("WR", team("WR", "t2", "Team 2"));
        positions.put("TE", team("TE", "t1", "Team 1"));
        assertThrows(IllegalArgumentException.class,
            () -> new TradeAssetPositionalContextAnalyzer.TeamPositionalContext(identity, positions));
    }

    private static LeaguePositionalPressureAnalyzer.TeamPositionPressure team(
        String position, String teamId, String teamName) {
        return new LeaguePositionalPressureAnalyzer.TeamPositionPressure(teamId, teamName, 10.0, 20.0,
            1, 1, 0, 0, LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED);
    }
}
