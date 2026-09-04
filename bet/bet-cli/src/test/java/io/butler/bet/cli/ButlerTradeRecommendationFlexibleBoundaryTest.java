package io.butler.bet.cli;

import io.butler.bet.intelligence.LeagueCompetitiveTierPolicy;
import io.butler.bet.intelligence.LeagueFlexibleSlotPressurePolicy;
import io.butler.bet.intelligence.LeagueFutureCapitalTierAnalyzer;
import io.butler.bet.intelligence.LeagueFutureCapitalTierPolicy;
import io.butler.bet.intelligence.LeagueLineupRequirementsAnalyzer;
import io.butler.bet.intelligence.LeaguePositionalDepthAnalyzer;
import io.butler.bet.intelligence.LeaguePositionalPressureAnalyzer;
import io.butler.bet.intelligence.LeaguePositionalPressurePolicy;
import io.butler.bet.intelligence.LeagueRosterStrengthTierPolicy;
import io.butler.bet.intelligence.LeagueTeamPostureAnalyzer;
import io.butler.bet.intelligence.TeamPosturePolicy;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeAssetPositionalContextAnalyzer;
import io.butler.bet.intelligence.TradeAssetStrategicContextAnalyzer;
import io.butler.bet.intelligence.TradeFairnessPolicy;
import io.butler.bet.intelligence.TradeFlexibleCoverageMaterialLossAnalyzer;
import io.butler.bet.intelligence.TradeFlexibleRecommendationContextAnalyzer;
import io.butler.bet.intelligence.TradeMarketEdgePolicy;
import io.butler.bet.intelligence.TradeRecommendationPolicy;
import io.butler.bet.intelligence.TradeRecommendationVetoPolicy;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeRecommendationFlexibleBoundaryTest {
    private static final String LEAGUE = "l1";
    private static final String SOURCE = "source";
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void noFlexibleRequirementRemainsConclusiveAndDirectional() {
        var context = noFlexibleContext();

        var result = ButlerTradeRecommendationCli.recommend(
            context, TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM);

        assertTrue(result.evidenceStatus().complete());
        assertTrue(result.evidenceStatus().flexiblePressureAvailable());
        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.NO_FLEXIBLE_REQUIREMENT,
            context.flexible().sideA().pressure().tier());
        assertEquals(TradeFlexibleCoverageMaterialLossAnalyzer.AssessmentState.NOT_PROTECTED,
            result.flexibleLossAssessment().state());
        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, result.vetoAssessment().state());
        assertTrue(result.vetoAssessment().evaluated());
        assertEquals(TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED,
            result.packageRecommendation());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.REJECT, result.action());
    }

    @Test
    void superFlexAllowsCrossPositionReplacementAtTwentyFivePercentBoundary() {
        var context = superFlexContext();

        var result = ButlerTradeRecommendationCli.recommend(
            context, TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM);

        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE,
            context.flexible().sideA().pressure().tier());
        assertEquals(TradeFlexibleCoverageMaterialLossAnalyzer.AssessmentState.WITHIN_TOLERANCE,
            result.flexibleLossAssessment().state());
        assertEquals(20.0, result.flexibleLossAssessment().preTradeCoverageValue());
        assertEquals(15.0, result.flexibleLossAssessment().postTradeCoverageValue());
        assertEquals(0.25, result.flexibleLossAssessment().lossFraction());
        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, result.vetoAssessment().state());
        assertEquals(TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED,
            result.packageRecommendation());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.REJECT, result.action());
    }

    private static TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport noFlexibleContext() {
        var sideA = tradeSide(tradePlayer("a-wr2", "WR", "a", "Alpha", 20.0));
        var sideB = tradeSide(tradePlayer("b-wr2", "WR", "b", "Bravo", 15.0));
        var trade = positionalTrade(sideA, sideB, 0, 0);
        var lineup = LeagueLineupRequirementsAnalyzer.interpret(LEAGUE, List.of("QB", "WR"));
        var depth = new LeaguePositionalDepthAnalyzer.DepthReport(
            LEAGUE, SOURCE, AS_OF, List.of(
                team("a", "Alpha", 100.0, 20.0),
                team("b", "Bravo", 100.0, 40.0),
                team("c", "Charlie", 100.0, 60.0),
                team("d", "Delta", 100.0, 80.0)));
        return TradeFlexibleRecommendationContextAnalyzer.compose(trade, lineup, depth);
    }

    private static TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport superFlexContext() {
        var sideA = tradeSide(tradePlayer("a-qb2", "QB", "a", "Alpha", 20.0));
        var sideB = tradeSide(tradePlayer("b-wr2", "WR", "b", "Bravo", 15.0));
        var trade = positionalTrade(sideA, sideB, 0, 1);
        var lineup = LeagueLineupRequirementsAnalyzer.interpret(
            LEAGUE, List.of("QB", "WR", "SUPER_FLEX"));
        var depth = new LeaguePositionalDepthAnalyzer.DepthReport(
            LEAGUE, SOURCE, AS_OF, List.of(
                teamWithSuperFlex("a", "Alpha", "a-qb2", 20.0, null, null),
                teamWithSuperFlex("b", "Bravo", "b-qb2", 40.0, "b-wr2", 15.0),
                teamWithSuperFlex("c", "Charlie", "c-qb2", 60.0, null, null),
                teamWithSuperFlex("d", "Delta", "d-qb2", 80.0, null, null)));
        return TradeFlexibleRecommendationContextAnalyzer.compose(trade, lineup, depth);
    }

    private static TradeAssetPositionalContextAnalyzer.TradePositionalContextReport positionalTrade(
        TradeAssetAnalyzer.TradeSide sideA,
        TradeAssetAnalyzer.TradeSide sideB,
        int flexSlots,
        int superFlexSlots) {
        var identityA = new TradeAssetStrategicContextAnalyzer.TeamIdentity("a", "Alpha");
        var identityB = new TradeAssetStrategicContextAnalyzer.TeamIdentity("b", "Bravo");
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
        availability.put("QB", new TradeAssetPositionalContextAnalyzer.PositionAvailability("QB", 1, true, null));
        availability.put("RB", new TradeAssetPositionalContextAnalyzer.PositionAvailability("RB", 0, true, null));
        availability.put("WR", new TradeAssetPositionalContextAnalyzer.PositionAvailability("WR", 1, true, null));
        availability.put("TE", new TradeAssetPositionalContextAnalyzer.PositionAvailability("TE", 0, true, null));

        return new TradeAssetPositionalContextAnalyzer.TradePositionalContextReport(
            strategic,
            LeaguePositionalPressurePolicy.POLICY_ID,
            LeagueLineupRequirementsAnalyzer.POLICY_ID,
            flexSlots,
            superFlexSlots,
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
            "QB", pressure(identity, "QB"),
            "RB", pressure(identity, "RB"),
            "WR", pressure(identity, "WR"),
            "TE", pressure(identity, "TE")));
    }

    private static LeaguePositionalPressureAnalyzer.TeamPositionPressure pressure(
        TradeAssetStrategicContextAnalyzer.TeamIdentity identity,
        String position) {
        return new LeaguePositionalPressureAnalyzer.TeamPositionPressure(
            identity.teamId(), identity.teamName(), 100.0, 100.0, 2, 2, 0, 0,
            LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED);
    }

    private static LeaguePositionalDepthAnalyzer.TeamDepth team(
        String id,
        String name,
        double starterValue,
        double benchValue) {
        return teamDepth(id, name,
            depthPlayer(id + "-qb1", "QB", starterValue),
            depthPlayer(id + "-wr1", "WR", starterValue),
            depthPlayer(id + "-wr2", "WR", benchValue));
    }

    private static LeaguePositionalDepthAnalyzer.TeamDepth teamWithSuperFlex(
        String id,
        String name,
        String secondQbId,
        double secondQbValue,
        String secondWrId,
        Double secondWrValue) {
        List<LeaguePositionalDepthAnalyzer.PlayerDepthValue> players = new ArrayList<>();
        players.add(depthPlayer(id + "-qb1", "QB", 100.0));
        players.add(depthPlayer(secondQbId, "QB", secondQbValue));
        players.add(depthPlayer(id + "-wr1", "WR", 100.0));
        if (secondWrId != null && secondWrValue != null) {
            players.add(depthPlayer(secondWrId, "WR", secondWrValue));
        }
        return teamDepth(id, name, players.toArray(LeaguePositionalDepthAnalyzer.PlayerDepthValue[]::new));
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

    private static LeaguePositionalDepthAnalyzer.PlayerDepthValue depthPlayer(
        String id,
        String position,
        double value) {
        return new LeaguePositionalDepthAnalyzer.PlayerDepthValue(
            id, id, position, "BN", value, AS_OF);
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
