package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeFlexibleRecommendationContextAnalyzerTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void composesLeagueRelativeFlexiblePressureForExplicitTradeTeams() {
        var result = TradeFlexibleRecommendationContextAnalyzer.compose(
            positional(), lineup(), depth("source"));

        assertTrue(result.flexible().flexiblePressureAvailable());
        assertEquals(1, result.flexible().flexSlots());
        assertEquals(0, result.flexible().superFlexSlots());
        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE,
            result.flexible().sideA().pressure().tier());
        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_BALANCED,
            result.flexible().sideB().pressure().tier());
        assertEquals(20.0, result.flexible().sideA().pressure().flexibleCoverageValue());
        assertEquals(40.0, result.flexible().sideB().pressure().flexibleCoverageValue());
    }

    @Test
    void rejectsDepthFromDifferentValueSource() {
        assertThrows(IllegalStateException.class, () ->
            TradeFlexibleRecommendationContextAnalyzer.compose(
                positional(), lineup(), depth("other-source")));
    }

    @Test
    void rejectsLineupExposureThatDiffersFromTradePositionalContext() {
        var superFlexLineup = LeagueLineupRequirementsAnalyzer.interpret(
            "l1", List.of("RB", "WR", "SUPER_FLEX"));
        assertThrows(IllegalStateException.class, () ->
            TradeFlexibleRecommendationContextAnalyzer.compose(
                positional(), superFlexLineup, depth("source")));
    }

    private static TradeAssetPositionalContextAnalyzer.TradePositionalContextReport positional() {
        var identityA = new TradeAssetStrategicContextAnalyzer.TeamIdentity("a", "Alpha");
        var identityB = new TradeAssetStrategicContextAnalyzer.TeamIdentity("b", "Bravo");
        var sideA = tradeSide(player("trade-a", "a", "Alpha", 20.0));
        var sideB = tradeSide(player("trade-b", "b", "Bravo", 40.0));
        var trade = new TradeAssetAnalyzer.TradeReport("l1", "source", AS_OF, sideA, sideB);
        var strategic = new TradeAssetStrategicContextAnalyzer.StrategicTradeReport(
            trade,
            "fairness-measurement",
            TradeFairnessPolicy.POLICY_ID,
            0.0,
            TradeFairnessPolicy.Classification.MARKET_FAIR,
            TradeMarketEdgePolicy.POLICY_ID,
            TradeMarketEdgePolicy.Direction.MARKET_FAIR,
            "posture-policy",
            true,
            "future-capital-policy",
            true,
            strategic(identityA),
            strategic(identityB));

        Map<String, TradeAssetPositionalContextAnalyzer.PositionAvailability> availability = new LinkedHashMap<>();
        availability.put("QB", new TradeAssetPositionalContextAnalyzer.PositionAvailability("QB", 0, true, null));
        availability.put("RB", new TradeAssetPositionalContextAnalyzer.PositionAvailability("RB", 1, true, null));
        availability.put("WR", new TradeAssetPositionalContextAnalyzer.PositionAvailability("WR", 1, true, null));
        availability.put("TE", new TradeAssetPositionalContextAnalyzer.PositionAvailability("TE", 0, true, null));

        return new TradeAssetPositionalContextAnalyzer.TradePositionalContextReport(
            strategic,
            LeaguePositionalPressurePolicy.POLICY_ID,
            LeagueLineupRequirementsAnalyzer.POLICY_ID,
            1,
            0,
            Map.copyOf(availability),
            positional(identityA),
            positional(identityB));
    }

    private static TradeAssetStrategicContextAnalyzer.TeamStrategicContext strategic(
        TradeAssetStrategicContextAnalyzer.TeamIdentity identity) {
        var posture = new LeagueTeamPostureAnalyzer.TeamPosture(
            identity.teamId(), identity.teamName(),
            LeagueCompetitiveTierPolicy.Tier.MIDDLE_TIER,
            LeagueRosterStrengthTierPolicy.Tier.MIDDLE_ROSTER_TIER,
            TeamPosturePolicy.Posture.MIDDLE_OR_MIXED);
        var capital = new LeagueFutureCapitalTierAnalyzer.TeamFutureCapital(
            identity.teamId(), identity.teamName(), 100.0, 1, 0, 0, 1, List.of(),
            LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL);
        return new TradeAssetStrategicContextAnalyzer.TeamStrategicContext(identity, posture, capital);
    }

    private static TradeAssetPositionalContextAnalyzer.TeamPositionalContext positional(
        TradeAssetStrategicContextAnalyzer.TeamIdentity identity) {
        return new TradeAssetPositionalContextAnalyzer.TeamPositionalContext(identity, Map.of(
            "QB", pressure(identity, 10.0),
            "RB", pressure(identity, 10.0),
            "WR", pressure(identity, 10.0),
            "TE", pressure(identity, 10.0)));
    }

    private static LeaguePositionalPressureAnalyzer.TeamPositionPressure pressure(
        TradeAssetStrategicContextAnalyzer.TeamIdentity identity,
        double value) {
        return new LeaguePositionalPressureAnalyzer.TeamPositionPressure(
            identity.teamId(), identity.teamName(), value, value, 1, 1, 0, 0,
            LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED);
    }

    private static LeagueLineupRequirementsAnalyzer.LineupRequirementsReport lineup() {
        return LeagueLineupRequirementsAnalyzer.interpret("l1", List.of("RB", "WR", "FLEX"));
    }

    private static LeaguePositionalDepthAnalyzer.DepthReport depth(String source) {
        return new LeaguePositionalDepthAnalyzer.DepthReport(
            "l1", source, AS_OF, List.of(
                team("a", "Alpha", 20.0),
                team("b", "Bravo", 40.0),
                team("c", "Charlie", 60.0),
                team("d", "Delta", 80.0)));
    }

    private static LeaguePositionalDepthAnalyzer.TeamDepth team(
        String id, String name, double flexValue) {
        Map<String, List<LeaguePositionalDepthAnalyzer.PlayerDepthValue>> grouped = new LinkedHashMap<>();
        grouped.put("RB", new ArrayList<>(List.of(depthPlayer(id + "-rb1", "RB", 100.0))));
        grouped.put("WR", new ArrayList<>(List.of(
            depthPlayer(id + "-wr1", "WR", 100.0),
            depthPlayer(id + "-wr2", "WR", flexValue))));
        Comparator<LeaguePositionalDepthAnalyzer.PlayerDepthValue> order = Comparator
            .comparingDouble(LeaguePositionalDepthAnalyzer.PlayerDepthValue::value).reversed()
            .thenComparing(LeaguePositionalDepthAnalyzer.PlayerDepthValue::playerName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(LeaguePositionalDepthAnalyzer.PlayerDepthValue::playerId);
        Map<String, LeaguePositionalDepthAnalyzer.PositionDepth> positions = new LinkedHashMap<>();
        grouped.forEach((position, players) -> {
            players.sort(order);
            positions.put(position, new LeaguePositionalDepthAnalyzer.PositionDepth(
                position, players.size(), players.size(), 0, 0, List.copyOf(players)));
        });
        return new LeaguePositionalDepthAnalyzer.TeamDepth(id, name, Map.copyOf(positions));
    }

    private static LeaguePositionalDepthAnalyzer.PlayerDepthValue depthPlayer(
        String id, String position, double value) {
        return new LeaguePositionalDepthAnalyzer.PlayerDepthValue(
            id, id, position, "BN", value, AS_OF);
    }

    private static TradeAssetAnalyzer.TradePlayer player(
        String id, String teamId, String teamName, double value) {
        return new TradeAssetAnalyzer.TradePlayer(
            id, id, "WR", "NFL", teamId, teamName, value, AS_OF, false);
    }

    private static TradeAssetAnalyzer.TradeSide tradeSide(TradeAssetAnalyzer.TradePlayer player) {
        return new TradeAssetAnalyzer.TradeSide(
            List.of(player), List.of(), player.value(), 1, 0, 0, 0);
    }
}
