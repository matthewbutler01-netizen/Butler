package io.butler.bet.cli;

import io.butler.bet.intelligence.LeagueCompetitiveTierPolicy;
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
import io.butler.bet.intelligence.TradeFlexiblePressureTransitionAnalyzer;
import io.butler.bet.intelligence.TradeFlexibleRecommendationContextAnalyzer;
import io.butler.bet.intelligence.TradeMarketEdgePolicy;
import io.butler.bet.intelligence.TradeRecommendationFlexibleTransitionMaterialLossPolicy;
import io.butler.bet.intelligence.TradeRecommendationPolicy;
import io.butler.bet.intelligence.TradeRecommendationVetoPolicy;
import io.butler.bet.intelligence.TradeStrategicFlexibleTransitionMaterialLossVetoDetector;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeRecommendationV5CliTest {
    private static final String LEAGUE = "l1";
    private static final String SOURCE = "source";
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void v5BlocksMaterialTransitionThatFrozenV4LeavesDirectional() {
        var context = context(40.0, 35.0, 20.0);

        var v4 = ButlerTradeRecommendationCli.recommend(
            context, TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM);
        var v5 = ButlerTradeRecommendationV5Cli.recommend(
            context, TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM);

        assertEquals(TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED,
            v4.packageRecommendation());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.REJECT, v4.action());
        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, v4.vetoAssessment().state());

        assertEquals(
            TradeFlexiblePressureTransitionAnalyzer.AssessmentState.MATERIAL_TRANSITION_TO_PRESSURE,
            v5.transitionAssessment().state());
        assertEquals(TradeRecommendationVetoPolicy.VetoState.BLOCKED, v5.vetoAssessment().state());
        assertEquals(
            TradeStrategicFlexibleTransitionMaterialLossVetoDetector.ReasonCode
                .FLEXIBLE_MATERIAL_LOSS_TRANSITION_TO_PRESSURE,
            v5.vetoAssessment().reasons().getFirst().code());
        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD, v5.packageRecommendation());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.HOLD, v5.action());
    }

    @Test
    void exactlyTwentyFivePercentTransitionRemainsDirectionalInV5() {
        var context = context(40.0, 35.0, 30.0);

        var result = ButlerTradeRecommendationV5Cli.recommend(
            context, TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM);

        assertEquals(
            TradeFlexiblePressureTransitionAnalyzer.AssessmentState.TRANSITION_WITHIN_TOLERANCE,
            result.transitionAssessment().state());
        assertEquals(0.25, result.transitionAssessment().lossFraction());
        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, result.vetoAssessment().state());
        assertEquals(TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED,
            result.packageRecommendation());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.REJECT, result.action());
    }

    @Test
    void v5OutputSurfacesTransitionPolicyTierMovementAndReason() {
        var context = context(40.0, 35.0, 20.0);
        var options = new ButlerTradeRecommendationCli.Options(
            LEAGUE,
            2026,
            TradeAssetAnalyzer.TradePackage.players(List.of("a-wr2")),
            TradeAssetAnalyzer.TradePackage.players(List.of("b-rb2")),
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            SOURCE,
            AS_OF);
        PrintStream original = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerTradeRecommendationV5Cli.print(context, options);
        } finally {
            System.setOut(original);
        }
        String output = bytes.toString();

        assertTrue(output.contains("Recommendation policy: "
            + TradeRecommendationFlexibleTransitionMaterialLossPolicy.POLICY_ID));
        assertTrue(output.contains("Strategic veto policy: "
            + TradeStrategicFlexibleTransitionMaterialLossVetoDetector.POLICY_ID));
        assertTrue(output.contains("Flexible pressure transition: FLEXIBLE_BALANCED -> FLEXIBLE_PRESSURE"));
        assertTrue(output.contains("Flexible transition state: MATERIAL_TRANSITION_TO_PRESSURE"));
        assertTrue(output.contains("Flexible transition coverage: 40.00 -> 20.00 (50.0% loss)"));
        assertTrue(output.contains("Veto reason: FLEX/SUPERFLEX transition to pressure: legal coverage value 40.00 -> 20.00"));
        assertTrue(output.contains("Package recommendation: HOLD"));
        assertTrue(output.contains("Action: HOLD"));
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
            "a", "Alpha",
            depthPlayer("a-rb1", "RB", 100.0),
            depthPlayer("a-wr1", "WR", 100.0),
            depthPlayer("a-wr2", "WR", flexValue));
    }

    private static LeaguePositionalDepthAnalyzer.TeamDepth teamB(double baselineFlexValue, double outgoingValue) {
        return teamDepth(
            "b", "Bravo",
            depthPlayer("b-rb1", "RB", 100.0),
            depthPlayer("b-rb2", "RB", outgoingValue),
            depthPlayer("b-wr1", "WR", 100.0),
            depthPlayer("b-wr2", "WR", baselineFlexValue));
    }

    private static LeaguePositionalDepthAnalyzer.TeamDepth team(String id, String name, double flexValue) {
        return teamDepth(
            id, name,
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
