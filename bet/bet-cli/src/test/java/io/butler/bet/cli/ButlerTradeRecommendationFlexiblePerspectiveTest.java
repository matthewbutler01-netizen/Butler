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

class ButlerTradeRecommendationFlexiblePerspectiveTest {
    private static final String LEAGUE = "l1";
    private static final String SOURCE = "source";
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void sameTradeCanBeBlockedForPressuredSideAndDirectionalForOppositePerspective() {
        var context = context();

        var sideA = ButlerTradeRecommendationCli.recommend(
            context, TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM);
        var sideB = ButlerTradeRecommendationCli.recommend(
            context, TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_B_TEAM);

        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE,
            context.flexible().sideA().pressure().tier());
        assertEquals(LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_BALANCED,
            context.flexible().sideB().pressure().tier());

        assertEquals(TradeFlexibleCoverageMaterialLossAnalyzer.AssessmentState.MATERIAL_LOSS,
            sideA.flexibleLossAssessment().state());
        assertEquals(20.0, sideA.flexibleLossAssessment().preTradeCoverageValue());
        assertEquals(10.0, sideA.flexibleLossAssessment().postTradeCoverageValue());
        assertEquals(TradeRecommendationVetoPolicy.VetoState.BLOCKED, sideA.vetoAssessment().state());
        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD, sideA.packageRecommendation());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.HOLD, sideA.action());

        assertEquals(TradeFlexibleCoverageMaterialLossAnalyzer.AssessmentState.NOT_PROTECTED,
            sideB.flexibleLossAssessment().state());
        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, sideB.vetoAssessment().state());
        assertTrue(sideB.vetoAssessment().evaluated());
        assertEquals(TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED,
            sideB.packageRecommendation());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.ACCEPT, sideB.action());
    }

    private static TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context() {
        var identityA = new TradeAssetStrategicContextAnalyzer.TeamIdentity("a", "Alpha");
        var identityB = new TradeAssetStrategicContextAnalyzer.TeamIdentity("b", "Bravo");
        var sideA = tradeSide(tradePlayer("a-wr2", "WR", "a", "Alpha", 20.0));
        var sideB = tradeSide(tradePlayer("b-rb2", "RB", "b", "Bravo", 10.0));
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
            LEAGUE, SOURCE, AS_OF, List.of(
                team("a", "Alpha", 20.0, null),
                team("b", "Bravo", 40.0, 10.0),
                team("c", "Charlie", 60.0, null),
                team("d", "Delta", 80.0, null)));
        return TradeFlexibleRecommendationContextAnalyzer.compose(positionalTrade, lineup, depth);
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

    private static LeaguePositionalDepthAnalyzer.TeamDepth team(
        String id,
        String name,
        double flexValue,
        Double secondRbValue) {
        List<LeaguePositionalDepthAnalyzer.PlayerDepthValue> players = new ArrayList<>();
        players.add(depthPlayer(id + "-rb1", "RB", 100.0));
        if (secondRbValue != null) {
            players.add(depthPlayer(id + "-rb2", "RB", secondRbValue));
        }
        players.add(depthPlayer(id + "-wr1", "WR", 100.0));
        players.add(depthPlayer(id + "-wr2", "WR", flexValue));
        return teamDepth(id, name, players);
    }

    private static LeaguePositionalDepthAnalyzer.TeamDepth teamDepth(
        String id,
        String name,
        List<LeaguePositionalDepthAnalyzer.PlayerDepthValue> players) {
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
