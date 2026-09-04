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
import io.butler.bet.intelligence.TradeStrategicFlexibleMaterialLossVetoDetector;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeRecommendationFlexibleCliTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);
    private static final String LEAGUE = "l1";
    private static final String SOURCE = "source";

    @Test
    void liveV4BlocksDirectionalRecommendationForMaterialFlexibleCoverageLoss() {
        var context = context(10.0, 4);

        var result = ButlerTradeRecommendationCli.recommend(
            context, TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM);

        assertTrue(result.evidenceStatus().complete());
        assertEquals(TradeFlexibleCoverageMaterialLossAnalyzer.AssessmentState.MATERIAL_LOSS,
            result.flexibleLossAssessment().state());
        assertEquals(20.0, result.flexibleLossAssessment().preTradeCoverageValue());
        assertEquals(10.0, result.flexibleLossAssessment().postTradeCoverageValue());
        assertEquals(0.50, result.flexibleLossAssessment().lossFraction());
        assertEquals(TradeRecommendationVetoPolicy.VetoState.BLOCKED, result.vetoAssessment().state());
        assertTrue(result.vetoAssessment().evaluated());
        assertEquals(List.of(
                TradeStrategicFlexibleMaterialLossVetoDetector.ReasonCode
                    .FLEXIBLE_PRESSURE_MATERIAL_POST_TRADE_COVERAGE_LOSS),
            result.vetoAssessment().reasons().stream()
                .map(TradeStrategicFlexibleMaterialLossVetoDetector.VetoReason::code)
                .toList());
        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD, result.packageRecommendation());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.HOLD, result.action());
    }

    @Test
    void liveV4AllowsExactlyTwentyFivePercentFlexibleCoverageLoss() {
        var context = context(15.0, 4);

        var result = ButlerTradeRecommendationCli.recommend(
            context, TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM);

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

    @Test
    void unavailableFlexiblePressureMakesLiveV4Inconclusive() {
        var context = context(10.0, 3);

        var result = ButlerTradeRecommendationCli.recommend(
            context, TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM);

        assertFalse(result.evidenceStatus().complete());
        assertFalse(result.evidenceStatus().flexiblePressureAvailable());
        assertEquals(TradeRecommendationPolicy.Recommendation.INCONCLUSIVE, result.packageRecommendation());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.INCONCLUSIVE, result.action());
        assertFalse(result.vetoAssessment().evaluated());
        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, result.vetoAssessment().state());
        assertNull(result.flexibleLossAssessment());
        assertEquals("unavailable governed evidence: flexible pressure",
            ButlerTradeRecommendationCli.formatInconclusiveReason(result.evidenceStatus()));
    }

    @Test
    void formatsFlexibleMaterialLossReasonWithLegalCoverageEvidence() {
        var reason = new TradeStrategicFlexibleMaterialLossVetoDetector.VetoReason(
            TradeStrategicFlexibleMaterialLossVetoDetector.ReasonCode
                .FLEXIBLE_PRESSURE_MATERIAL_POST_TRADE_COVERAGE_LOSS,
            null,
            20.0,
            10.0,
            0.50);

        assertEquals(
            "FLEX/SUPERFLEX pressure: legal coverage value 20.00 -> 10.00 (50.0% loss; material when loss > 25.0%)",
            ButlerTradeRecommendationCli.formatVetoReason(reason));
    }

    @Test
    void liveV4PrintSurfacesFlexiblePoliciesEvidenceAndVeto() {
        var context = context(10.0, 4);
        var options = new ButlerTradeRecommendationCli.Options(
            LEAGUE,
            2026,
            TradeAssetAnalyzer.TradePackage.players(List.of("a-wr2")),
            TradeAssetAnalyzer.TradePackage.players(List.of("b-rb2")),
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            SOURCE,
            AS_OF);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            ButlerTradeRecommendationCli.print(context, options);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains(
            "Recommendation policy: trade-recommendation-v4-market-first-flexible-material-loss-veto"));
        assertTrue(output.contains(
            "Strategic veto policy: trade-strategic-veto-v3-material-protected-value-plus-flexible-coverage-loss"));
        assertTrue(output.contains(
            "Evidence gates: market-direction=true posture=true future-capital=true positional-pressure=true flexible-pressure=true"));
        assertTrue(output.contains("Flexible pressure: FLEXIBLE_PRESSURE"));
        assertTrue(output.contains("Flexible protected coverage: 20.00 -> 10.00 (50.0% loss)"));
        assertTrue(output.contains("Strategic veto: BLOCKED"));
        assertTrue(output.contains("Package recommendation: HOLD"));
        assertTrue(output.contains("Action: HOLD"));
    }

    private static TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context(
        double incomingValue,
        int leagueTeams) {
        return TradeFlexibleRecommendationContextAnalyzer.compose(
            positionalTrade(incomingValue),
            lineup(),
            depth(incomingValue, leagueTeams));
    }

    private static TradeAssetPositionalContextAnalyzer.TradePositionalContextReport positionalTrade(
        double incomingValue) {
        var identityA = new TradeAssetStrategicContextAnalyzer.TeamIdentity("a", "Alpha");
        var identityB = new TradeAssetStrategicContextAnalyzer.TeamIdentity("b", "Bravo");
        var sideA = tradeSide(tradePlayer("a-wr2", "WR", "a", "Alpha", 20.0));
        var sideB = tradeSide(tradePlayer("b-rb2", "RB", "b", "Bravo", incomingValue));
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
            identity.teamId(),
            identity.teamName(),
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
            identity.teamId(), identity.teamName(), 100.0, 100.0, 1, 1, 0, 0,
            LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED);
    }

    private static LeagueLineupRequirementsAnalyzer.LineupRequirementsReport lineup() {
        return LeagueLineupRequirementsAnalyzer.interpret(
            LEAGUE, List.of("RB", "WR", "FLEX"));
    }

    private static LeaguePositionalDepthAnalyzer.DepthReport depth(
        double incomingValue,
        int leagueTeams) {
        List<LeaguePositionalDepthAnalyzer.TeamDepth> teams = new ArrayList<>();
        teams.add(team("a", "Alpha", 20.0, null));
        teams.add(team("b", "Bravo", 40.0, incomingValue));
        teams.add(team("c", "Charlie", 60.0, null));
        if (leagueTeams >= 4) teams.add(team("d", "Delta", 80.0, null));
        return new LeaguePositionalDepthAnalyzer.DepthReport(
            LEAGUE, SOURCE, AS_OF, List.copyOf(teams));
    }

    private static LeaguePositionalDepthAnalyzer.TeamDepth team(
        String id,
        String name,
        double flexibleWrValue,
        Double secondRbValue) {
        Map<String, List<LeaguePositionalDepthAnalyzer.PlayerDepthValue>> grouped = new LinkedHashMap<>();
        List<LeaguePositionalDepthAnalyzer.PlayerDepthValue> rbs = new ArrayList<>();
        rbs.add(depthPlayer(id + "-rb1", "RB", 100.0));
        if (secondRbValue != null) {
            rbs.add(depthPlayer(id + "-rb2", "RB", secondRbValue));
        }
        grouped.put("RB", rbs);
        grouped.put("WR", new ArrayList<>(List.of(
            depthPlayer(id + "-wr1", "WR", 100.0),
            depthPlayer(id + "-wr2", "WR", flexibleWrValue))));

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
