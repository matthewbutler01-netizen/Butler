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
    void rejectsUnexpectedPositions() {
        var identity = new TradeAssetStrategicContextAnalyzer.TeamIdentity("t1", "Team 1");
        Map<String, LeaguePositionalPressureAnalyzer.TeamPositionPressure> positions = corePositions("t1", "Team 1");
        positions.put("K", team("K", "t1", "Team 1"));
        assertThrows(IllegalArgumentException.class,
            () -> new TradeAssetPositionalContextAnalyzer.TeamPositionalContext(identity, positions));
    }

    @Test
    void rejectsPositionEvidenceFromDifferentTeam() {
        var identity = new TradeAssetStrategicContextAnalyzer.TeamIdentity("t1", "Team 1");
        Map<String, LeaguePositionalPressureAnalyzer.TeamPositionPressure> positions = corePositions("t1", "Team 1");
        positions.put("WR", team("WR", "t2", "Team 2"));
        assertThrows(IllegalArgumentException.class,
            () -> new TradeAssetPositionalContextAnalyzer.TeamPositionalContext(identity, positions));
    }

    private static Map<String, LeaguePositionalPressureAnalyzer.TeamPositionPressure> corePositions(
        String teamId, String teamName) {
        Map<String, LeaguePositionalPressureAnalyzer.TeamPositionPressure> positions = new LinkedHashMap<>();
        positions.put("QB", team("QB", teamId, teamName));
        positions.put("RB", team("RB", teamId, teamName));
        positions.put("WR", team("WR", teamId, teamName));
        positions.put("TE", team("TE", teamId, teamName));
        return positions;
    }

    private static LeaguePositionalPressureAnalyzer.TeamPositionPressure team(
        String position, String teamId, String teamName) {
        return new LeaguePositionalPressureAnalyzer.TeamPositionPressure(teamId, teamName, 10.0, 20.0,
            1, 1, 0, 0, LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED);
    }
}
