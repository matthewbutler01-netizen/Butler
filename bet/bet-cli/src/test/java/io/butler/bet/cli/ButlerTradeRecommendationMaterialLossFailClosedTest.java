package io.butler.bet.cli;

import io.butler.bet.intelligence.LeagueCompetitiveTierPolicy;
import io.butler.bet.intelligence.LeagueFutureCapitalTierAnalyzer;
import io.butler.bet.intelligence.LeagueFutureCapitalTierPolicy;
import io.butler.bet.intelligence.LeaguePositionalPressureAnalyzer;
import io.butler.bet.intelligence.LeaguePositionalPressurePolicy;
import io.butler.bet.intelligence.LeagueRosterStrengthTierPolicy;
import io.butler.bet.intelligence.LeagueTeamPostureAnalyzer;
import io.butler.bet.intelligence.TeamPosturePolicy;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeAssetPositionalContextAnalyzer;
import io.butler.bet.intelligence.TradeAssetStrategicContextAnalyzer;
import io.butler.bet.intelligence.TradeFairnessPolicy;
import io.butler.bet.intelligence.TradeMarketEdgePolicy;
import io.butler.bet.intelligence.TradeRecommendationPolicy;
import io.butler.bet.intelligence.TradeRecommendationVetoPolicy;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ButlerTradeRecommendationMaterialLossFailClosedTest {
    @Test
    void marketUnavailableSkipsMaterialVetoForMissingProtectedValue() {
        var result = ButlerTradeRecommendationCli.recommend(
            report(missingPick("missing-pick")),
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM);

        assertInconclusiveWithoutVetoEvaluation(result);
    }

    @Test
    void marketUnavailableSkipsMaterialVetoForStaleProtectedValue() {
        var result = ButlerTradeRecommendationCli.recommend(
            report(stalePick("stale-pick")),
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM);

        assertInconclusiveWithoutVetoEvaluation(result);
    }

    private static void assertInconclusiveWithoutVetoEvaluation(
        ButlerTradeRecommendationCli.RecommendationResult result) {
        assertEquals(TradeRecommendationPolicy.Recommendation.INCONCLUSIVE, result.packageRecommendation());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.INCONCLUSIVE, result.action());
        assertFalse(result.evidenceStatus().complete());
        assertFalse(result.vetoAssessment().evaluated());
        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, result.vetoAssessment().state());
        assertEquals(List.of(), result.vetoAssessment().reasons());
    }

    private static TradeAssetPositionalContextAnalyzer.TradePositionalContextReport report(
        TradeAssetAnalyzer.TradeDraftPick protectedPick) {
        var identityA = new TradeAssetStrategicContextAnalyzer.TeamIdentity("a", "Team A");
        var identityB = new TradeAssetStrategicContextAnalyzer.TeamIdentity("b", "Team B");
        var sideA = new TradeAssetAnalyzer.TradeSide(
            List.of(), List.of(protectedPick), protectedPick.value() == null ? 0.0 : protectedPick.value(),
            0, 0, protectedPick.value() == null ? 0 : 1, protectedPick.value() == null ? 1 : 0);
        var sideBPlayer = new TradeAssetAnalyzer.TradePlayer(
            "wr-b", "WR B", "WR", "NFL", "b", "Team B", 100.0,
            LocalDate.of(2026, 9, 1), false);
        var sideB = new TradeAssetAnalyzer.TradeSide(List.of(sideBPlayer), List.of(), 100.0, 1, 0, 0, 0);
        var trade = new TradeAssetAnalyzer.TradeReport("l1", "source", null, sideA, sideB);

        var strategic = new TradeAssetStrategicContextAnalyzer.StrategicTradeReport(
            trade,
            "fairness-measurement",
            "fairness",
            null,
            TradeFairnessPolicy.Classification.UNAVAILABLE,
            "market-edge",
            TradeMarketEdgePolicy.Direction.UNAVAILABLE,
            "posture",
            true,
            "future-capital",
            true,
            strategic(identityA, LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL),
            strategic(identityB, LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL));

        Map<String, TradeAssetPositionalContextAnalyzer.PositionAvailability> availability = Map.of(
            "QB", available("QB"),
            "RB", available("RB"),
            "WR", available("WR"),
            "TE", available("TE"));
        return new TradeAssetPositionalContextAnalyzer.TradePositionalContextReport(
            strategic,
            "positional-pressure",
            "lineup",
            0,
            0,
            availability,
            positional(identityA),
            positional(identityB));
    }

    private static TradeAssetStrategicContextAnalyzer.TeamStrategicContext strategic(
        TradeAssetStrategicContextAnalyzer.TeamIdentity identity,
        LeagueFutureCapitalTierPolicy.Tier tier) {
        var posture = new LeagueTeamPostureAnalyzer.TeamPosture(
            identity.teamId(), identity.teamName(),
            LeagueCompetitiveTierPolicy.Tier.MIDDLE_TIER,
            LeagueRosterStrengthTierPolicy.Tier.MIDDLE_ROSTER_TIER,
            TeamPosturePolicy.Posture.MIDDLE_OR_MIXED);
        var capital = new LeagueFutureCapitalTierAnalyzer.TeamFutureCapital(
            identity.teamId(), identity.teamName(), 100.0, 1, 0, 0, 1, List.of(), tier);
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
            identity.teamId(), identity.teamName(), 50.0, 75.0, 3, 3, 0, 0,
            LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED);
    }

    private static TradeAssetPositionalContextAnalyzer.PositionAvailability available(String position) {
        return new TradeAssetPositionalContextAnalyzer.PositionAvailability(position, 1, true, null);
    }

    private static TradeAssetAnalyzer.TradeDraftPick missingPick(String id) {
        return new TradeAssetAnalyzer.TradeDraftPick(
            id, 2027, 1, "2027 1st", "a", "Team A", "a", "Team A",
            null, null, null, false);
    }

    private static TradeAssetAnalyzer.TradeDraftPick stalePick(String id) {
        return new TradeAssetAnalyzer.TradeDraftPick(
            id, 2027, 1, "2027 1st", "a", "Team A", "a", "Team A",
            null, 100.0, LocalDate.of(2026, 8, 1), true);
    }
}
