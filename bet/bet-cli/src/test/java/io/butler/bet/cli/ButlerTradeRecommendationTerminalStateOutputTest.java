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
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerTradeRecommendationTerminalStateOutputTest {
    @Test
    void printsExactMarketFairHoldContract() {
        String output = capture(TradeMarketEdgePolicy.Direction.MARKET_FAIR);

        assertEquals(lines(
            "Trade recommendation (conservative market-first material-loss veto)",
            "League ID: l1",
            "Season: 2026",
            "Perspective: Team A [a]",
            "Recommendation policy: trade-recommendation-v3-market-first-material-loss-veto",
            "Strategic veto policy: trade-strategic-veto-v2-material-protected-value-loss",
            "Protected value flow policy: trade-protected-value-flow-v1-current-valued-assets",
            "Protected value materiality policy: trade-protected-value-materiality-v1-25-percent-loss",
            "Perspective policy: trade-team-perspective-v1-explicit-owner",
            "Evidence complete: true",
            "Evidence gates: market-direction=true posture=true future-capital=true positional-pressure=true",
            "Strategic veto: CLEAR",
            "Package recommendation: HOLD",
            "Action: HOLD",
            "Reason: the governed market comparison is inside the fairness band.",
            "No hidden weighting, side flipping, or strategic score blending is applied."), output);
    }

    @Test
    void printsExactInconclusiveContractWhenMarketDirectionUnavailable() {
        String output = capture(TradeMarketEdgePolicy.Direction.UNAVAILABLE);

        assertEquals(lines(
            "Trade recommendation (conservative market-first material-loss veto)",
            "League ID: l1",
            "Season: 2026",
            "Perspective: Team A [a]",
            "Recommendation policy: trade-recommendation-v3-market-first-material-loss-veto",
            "Strategic veto policy: trade-strategic-veto-v2-material-protected-value-loss",
            "Protected value flow policy: trade-protected-value-flow-v1-current-valued-assets",
            "Protected value materiality policy: trade-protected-value-materiality-v1-25-percent-loss",
            "Perspective policy: trade-team-perspective-v1-explicit-owner",
            "Evidence complete: false",
            "Evidence gates: market-direction=false posture=true future-capital=true positional-pressure=true",
            "Strategic veto: NOT_EVALUATED",
            "Package recommendation: INCONCLUSIVE",
            "Action: INCONCLUSIVE",
            "Reason: unavailable governed evidence: market direction.",
            "No hidden weighting, side flipping, or strategic score blending is applied."), output);
    }

    private static String capture(TradeMarketEdgePolicy.Direction marketEdge) {
        var report = report(marketEdge);
        var options = new ButlerTradeRecommendationCli.Options(
            "l1",
            2026,
            TradeAssetAnalyzer.TradePackage.players(List.of("rb-a")),
            TradeAssetAnalyzer.TradePackage.players(List.of("wr-b")),
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            null,
            null);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            ButlerTradeRecommendationCli.print(report, options);
        } finally {
            System.setOut(original);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String lines(String... values) {
        return String.join(System.lineSeparator(), values) + System.lineSeparator();
    }

    private static TradeAssetPositionalContextAnalyzer.TradePositionalContextReport report(
        TradeMarketEdgePolicy.Direction marketEdge) {
        var identityA = new TradeAssetStrategicContextAnalyzer.TeamIdentity("a", "Team A");
        var identityB = new TradeAssetStrategicContextAnalyzer.TeamIdentity("b", "Team B");
        var sideA = side(player("rb-a", "RB", "a", "Team A"));
        var sideB = side(player("wr-b", "WR", "b", "Team B"));
        var trade = new TradeAssetAnalyzer.TradeReport("l1", "source", null, sideA, sideB);

        TradeFairnessPolicy.Classification fairness = switch (marketEdge) {
            case MARKET_FAIR -> TradeFairnessPolicy.Classification.MARKET_FAIR;
            case UNAVAILABLE -> TradeFairnessPolicy.Classification.UNAVAILABLE;
            case SIDE_A_MARKET_EDGE, SIDE_B_MARKET_EDGE -> TradeFairnessPolicy.Classification.OUTSIDE_FAIRNESS_BAND;
        };
        Double gap = marketEdge == TradeMarketEdgePolicy.Direction.UNAVAILABLE ? null : 0.0;

        var strategic = new TradeAssetStrategicContextAnalyzer.StrategicTradeReport(
            trade,
            "fairness-measurement",
            "fairness",
            gap,
            fairness,
            "market-edge",
            marketEdge,
            "posture",
            true,
            "future-capital",
            true,
            strategic(identityA),
            strategic(identityB));

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
        TradeAssetStrategicContextAnalyzer.TeamIdentity identity) {
        var posture = new LeagueTeamPostureAnalyzer.TeamPosture(
            identity.teamId(),
            identity.teamName(),
            LeagueCompetitiveTierPolicy.Tier.MIDDLE_TIER,
            LeagueRosterStrengthTierPolicy.Tier.MIDDLE_ROSTER_TIER,
            TeamPosturePolicy.Posture.MIDDLE_OR_MIXED);
        var capital = new LeagueFutureCapitalTierAnalyzer.TeamFutureCapital(
            identity.teamId(),
            identity.teamName(),
            100.0,
            1,
            0,
            0,
            1,
            List.of(),
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
            identity.teamId(),
            identity.teamName(),
            50.0,
            75.0,
            3,
            3,
            0,
            0,
            LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED);
    }

    private static TradeAssetPositionalContextAnalyzer.PositionAvailability available(String position) {
        return new TradeAssetPositionalContextAnalyzer.PositionAvailability(position, 1, true, null);
    }

    private static TradeAssetAnalyzer.TradeSide side(TradeAssetAnalyzer.TradePlayer player) {
        return new TradeAssetAnalyzer.TradeSide(List.of(player), List.of(), 100.0, 1, 0, 0, 0);
    }

    private static TradeAssetAnalyzer.TradePlayer player(
        String id, String position, String teamId, String teamName) {
        return new TradeAssetAnalyzer.TradePlayer(
            id,
            id,
            position,
            "NFL",
            teamId,
            teamName,
            100.0,
            LocalDate.of(2026, 9, 1),
            false);
    }
}
