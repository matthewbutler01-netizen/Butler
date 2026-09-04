package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeFlexiblePressureTransitionAnalyzerTest {
    private static final String LEAGUE = "l1";
    private static final String SOURCE = "source";
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void balancedTeamMovingIntoPressureWithMoreThanTwentyFivePercentLossIsMaterialTransition() {
        var context = context(40.0, 35.0, 20.0);
        var result = TradeFlexiblePressureTransitionAnalyzer.assess(
            context,
            context.flexible().sideA(),
            context.trade().strategic().trade().sideA(),
            context.trade().strategic().trade().sideB());

        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_BALANCED, result.preTradeTier());
        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE, result.postTradeTier());
        assertEquals(40.0, result.preTradeCoverageValue());
        assertEquals(20.0, result.postTradeCoverageValue());
        assertEquals(0.5, result.lossFraction());
        assertEquals(
            TradeFlexiblePressureTransitionAnalyzer.AssessmentState.MATERIAL_TRANSITION_TO_PRESSURE,
            result.state());
        assertTrue(result.available());
        assertTrue(result.transitionedToPressure());
        assertTrue(result.materialTransitionToPressure());
    }

    @Test
    void strengthTeamMovingIntoPressureWithMoreThanTwentyFivePercentLossIsMaterialTransition() {
        var context = context(100.0, 35.0, 20.0);
        var result = TradeFlexiblePressureTransitionAnalyzer.assess(
            context,
            context.flexible().sideA(),
            context.trade().strategic().trade().sideA(),
            context.trade().strategic().trade().sideB());

        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_STRENGTH, result.preTradeTier());
        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE, result.postTradeTier());
        assertEquals(100.0, result.preTradeCoverageValue());
        assertEquals(20.0, result.postTradeCoverageValue());
        assertEquals(0.8, result.lossFraction());
        assertEquals(
            TradeFlexiblePressureTransitionAnalyzer.AssessmentState.MATERIAL_TRANSITION_TO_PRESSURE,
            result.state());
        assertTrue(result.available());
        assertTrue(result.transitionedToPressure());
        assertTrue(result.materialTransitionToPressure());
    }

    @Test
    void exactlyTwentyFivePercentLossCanTransitionToPressureWithoutBeingMaterial() {
        var context = context(40.0, 35.0, 30.0);
        var result = TradeFlexiblePressureTransitionAnalyzer.assess(
            context,
            context.flexible().sideA(),
            context.trade().strategic().trade().sideA(),
            context.trade().strategic().trade().sideB());

        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_BALANCED, result.preTradeTier());
        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE, result.postTradeTier());
        assertEquals(0.25, result.lossFraction());
        assertEquals(
            TradeFlexiblePressureTransitionAnalyzer.AssessmentState.TRANSITION_WITHIN_TOLERANCE,
            result.state());
        assertTrue(result.transitionedToPressure());
        assertFalse(result.materialTransitionToPressure());
    }

    @Test
    void existingPressureRemainsSeparateFromNewTransitionEvidence() {
        var context = context(20.0, 35.0, 10.0);
        var result = TradeFlexiblePressureTransitionAnalyzer.assess(
            context,
            context.flexible().sideA(),
            context.trade().strategic().trade().sideA(),
            context.trade().strategic().trade().sideB());

        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE, result.preTradeTier());
        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE, result.postTradeTier());
        assertEquals(0.5, result.lossFraction());
        assertEquals(TradeFlexiblePressureTransitionAnalyzer.AssessmentState.NO_TRANSITION, result.state());
        assertFalse(result.transitionedToPressure());
        assertFalse(result.materialTransitionToPressure());
    }

    @Test
    void bothTradeTeamsAreReconstructedBeforeLeagueRelativeReranking() {
        var context = context(40.0, 35.0, 20.0);
        var postDepth = TradeFlexiblePostTradeDepthAnalyzer.apply(
            context,
            context.flexible().sideA(),
            context.trade().strategic().trade().sideA(),
            context.trade().strategic().trade().sideB());
        var coverage = LeagueFlexibleSlotCoverageAnalyzer.compose(context.lineup(), postDepth.leagueDepth());
        var byTeam = coverage.teams().stream().collect(java.util.stream.Collectors.toMap(
            LeagueFlexibleSlotCoverageAnalyzer.TeamFlexibleCoverage::teamId,
            LeagueFlexibleSlotCoverageAnalyzer.TeamFlexibleCoverage::flexibleCoverageValue));

        assertEquals(20.0, byTeam.get("a"));
        assertEquals(40.0, byTeam.get("b"));
    }

    private static TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context(
        double sideAFlexValue,
        double sideBBaselineFlexValue,
        double sideBOutgoingValue) {
        var identityA = new TradeAssetStrategicContextAnalyzer.TeamIdentity("a", "Alpha");
        var identityB = new TradeAssetStrategicContextAnalyzer.TeamIdentity("b", "Bravo");
        var sideA = tradeSide(tradePlayer("a-wr2", "WR", "a", "Alpha", sideAFlexValue));
        var sideB = tradeSide(tradePlayer("b-rb2", "RB", "b", "Bravo", sideBOutgoingValue));
        var trade = new TradeAssetAnalyzer.TradeReport(LEAGUE, SOURCE, AS_OF, sideA, sideB);
        var strategic = new TradeAssetStrategicContextAnalyzer.StrategicTradeReport(
            trade,
            "fairness-measurement",
            TradeFairnessPolicy.POLICY_ID,
            40.0,
            TradeFairnessPolicy.Classification.OUTSIDE_FAIRNESS_BAND,
            TradeMarketEdgePolicy.POLICY_ID,
            TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
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

        var positionalTrade = new TradeAssetPositionalContextAnalyzer.TradePositionalContextReport(
            strategic,
            LeaguePositionalPressurePolicy.POLICY_ID,
            LeagueLineupRequirementsAnalyzer.POLICY_ID,
            1,
            0,
            Map.copyOf(availability),
            positional(identityA),
            positional(identityB));
        var lineup = LeagueLineupRequirementsAnalyzer.interpret(LEAGUE, List.of("RB", "WR", "FLEX"));
        var depth = new LeaguePositionalDepthAnalyzer.DepthReport(
            LEAGUE,
            SOURCE,
            AS_OF,
            List.of(
                teamA(sideAFlexValue),
                teamB(sideBBaselineFlexValue, sideBOutgoingValue),
                team("c", "Charlie", 60.0),
                team("d", "Delta", 80.0)));
        return TradeFlexibleRecommendationContextAnalyzer.compose(positionalTrade, lineup, depth);
    }

    private static LeaguePositionalDepthAnalyzer.TeamDepth teamA(double flexValue) {
        return teamDepth(
            "a",
            "Alpha",
            depthPlayer("a-rb1", "RB", 100.0),
            depthPlayer("a-wr1", "WR", 100.0),
            depthPlayer("a-wr2", "WR", flexValue));
    }

    private static LeaguePositionalDepthAnalyzer.TeamDepth teamB(double baselineFlexValue, double outgoingValue) {
        return teamDepth(
            "b",
            "Bravo",
            depthPlayer("b-rb1", "RB", 100.0),
            depthPlayer("b-rb2", "RB", outgoingValue),
            depthPlayer("b-wr1", "WR", 100.0),
            depthPlayer("b-wr2", "WR", baselineFlexValue));
    }

    private static LeaguePositionalDepthAnalyzer.TeamDepth team(String id, String name, double flexValue) {
        return teamDepth(
            id,
            name,
            depthPlayer(id + "-rb1", "RB", 100.0),
            depthPlayer(id + "-wr1", "WR", 100.0),
            depthPlayer(id + "-wr2", "WR", flexValue));
    }

    private static LeaguePositionalDepthAnalyzer.TeamDepth teamDepth(
        String id,
        String name,
        LeaguePositionalDepthAnalyzer.PlayerDepthValue... players) {
        Map<String, List<LeaguePositionalDepthAnalyzer.PlayerDepthValue>> grouped = new LinkedHashMap<>();
        for (var player : players) {
            grouped.computeIfAbsent(player.position(), ignored -> new ArrayList<>()).add(player);
        }
        Comparator<LeaguePositionalDepthAnalyzer.PlayerDepthValue> order = Comparator
            .comparingDouble(LeaguePositionalDepthAnalyzer.PlayerDepthValue::value).reversed()
            .thenComparing(LeaguePositionalDepthAnalyzer.PlayerDepthValue::playerName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(LeaguePositionalDepthAnalyzer.PlayerDepthValue::playerId);
        Map<String, LeaguePositionalDepthAnalyzer.PositionDepth> positions = new LinkedHashMap<>();
        grouped.forEach((position, values) -> {
            values.sort(order);
            positions.put(position, new LeaguePositionalDepthAnalyzer.PositionDepth(
                position, values.size(), values.size(), 0, 0, List.copyOf(values)));
        });
        return new LeaguePositionalDepthAnalyzer.TeamDepth(id, name, Map.copyOf(positions));
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
            "QB", pressure(identity),
            "RB", pressure(identity),
            "WR", pressure(identity),
            "TE", pressure(identity)));
    }

    private static LeaguePositionalPressureAnalyzer.TeamPositionPressure pressure(
        TradeAssetStrategicContextAnalyzer.TeamIdentity identity) {
        return new LeaguePositionalPressureAnalyzer.TeamPositionPressure(
            identity.teamId(), identity.teamName(), 100.0, 100.0, 2, 2, 0, 0,
            LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED);
    }

    private static LeaguePositionalDepthAnalyzer.PlayerDepthValue depthPlayer(
        String id,
        String position,
        double value) {
        return new LeaguePositionalDepthAnalyzer.PlayerDepthValue(id, id, position, "BN", value, AS_OF);
    }

    private static TradeAssetAnalyzer.TradePlayer tradePlayer(
        String id,
        String position,
        String teamId,
        String teamName,
        double value) {
        return new TradeAssetAnalyzer.TradePlayer(
            id, id, position, "NFL", teamId, teamName, value, AS_OF, false);
    }

    private static TradeAssetAnalyzer.TradeSide tradeSide(TradeAssetAnalyzer.TradePlayer player) {
        return new TradeAssetAnalyzer.TradeSide(
            List.of(player), List.of(), player.value(), 1, 0, 0, 0);
    }
}
