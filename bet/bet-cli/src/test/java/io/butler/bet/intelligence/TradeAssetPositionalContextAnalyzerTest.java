package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    @Test
    void rejectsAvailablePositionWithInsufficientEvidenceTier() {
        var report = position(true, null, LeaguePositionalPressurePolicy.Tier.INSUFFICIENT_EVIDENCE);
        assertThrows(IllegalStateException.class,
            () -> TradeAssetPositionalContextAnalyzer.validateAvailabilityTiers(report));
    }

    @Test
    void rejectsUnavailablePositionWithClassifiedTier() {
        var report = position(false, "missing current values", LeaguePositionalPressurePolicy.Tier.POSITION_PRESSURE);
        assertThrows(IllegalStateException.class,
            () -> TradeAssetPositionalContextAnalyzer.validateAvailabilityTiers(report));
    }

    @Test
    void acceptsConsistentAvailabilityAndTiers() {
        assertDoesNotThrow(() -> TradeAssetPositionalContextAnalyzer.validateAvailabilityTiers(
            position(true, null, LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED)));
        assertDoesNotThrow(() -> TradeAssetPositionalContextAnalyzer.validateAvailabilityTiers(
            position(false, "missing current values", LeaguePositionalPressurePolicy.Tier.INSUFFICIENT_EVIDENCE)));
    }

    private static LeaguePositionalPressureAnalyzer.PositionPressure position(
        boolean available, String reason, LeaguePositionalPressurePolicy.Tier tier) {
        return new LeaguePositionalPressureAnalyzer.PositionPressure(
            "QB", 1, available, reason,
            List.of(new LeaguePositionalPressureAnalyzer.TeamPositionPressure(
                "t1", "Team 1", 10.0, 20.0, 1, 1, 0, 0, tier)));
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
