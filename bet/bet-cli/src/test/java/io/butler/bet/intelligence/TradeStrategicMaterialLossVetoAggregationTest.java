package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeStrategicMaterialLossVetoAggregationTest {
    private static final TradeAssetStrategicContextAnalyzer.TeamIdentity TEAM =
        new TradeAssetStrategicContextAnalyzer.TeamIdentity("t1", "Team One");

    @Test
    void aggregatesMultipleFuturePicksBeforeApplyingTwentyFivePercentBoundary() {
        var clear = TradeStrategicMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL),
            positional(LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED),
            side(List.of(), List.of(pick("out-1", 60.0), pick("out-2", 40.0))),
            side(List.of(), List.of(pick("in-1", 50.0), pick("in-2", 25.0))));
        var blocked = TradeStrategicMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL),
            positional(LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED),
            side(List.of(), List.of(pick("out-1", 60.0), pick("out-2", 40.0))),
            side(List.of(), List.of(pick("in-1", 50.0), pick("in-2", 24.0))));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, clear.state());
        assertEquals(TradeRecommendationVetoPolicy.VetoState.BLOCKED, blocked.state());
        var reason = blocked.reasons().getFirst();
        assertEquals(100.0, reason.outgoingProtectedValue());
        assertEquals(74.0, reason.incomingProtectedValue());
        assertEquals(0.26, reason.lossFraction(), 0.000001);
    }

    @Test
    void aggregatesMultipleSamePositionPlayersBeforeApplyingBoundary() {
        var clear = TradeStrategicMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL),
            positional(LeaguePositionalPressurePolicy.Tier.POSITION_PRESSURE),
            side(List.of(player("wr-out-1", "WR", 60.0), player("wr-out-2", "WR", 40.0)), List.of()),
            side(List.of(player("wr-in-1", "WR", 50.0), player("wr-in-2", "WR", 25.0)), List.of()));
        var blocked = TradeStrategicMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL),
            positional(LeaguePositionalPressurePolicy.Tier.POSITION_PRESSURE),
            side(List.of(player("wr-out-1", "WR", 60.0), player("wr-out-2", "WR", 40.0)), List.of()),
            side(List.of(player("wr-in-1", "WR", 50.0), player("wr-in-2", "WR", 24.0)), List.of()));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, clear.state());
        assertEquals(TradeRecommendationVetoPolicy.VetoState.BLOCKED, blocked.state());
        var reason = blocked.reasons().getFirst();
        assertEquals(TradeStrategicMaterialLossVetoDetector.ReasonCode.POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS,
            reason.code());
        assertEquals("WR", reason.position());
        assertEquals(100.0, reason.outgoingProtectedValue());
        assertEquals(74.0, reason.incomingProtectedValue());
        assertEquals(0.26, reason.lossFraction(), 0.000001);
    }

    private static TradeAssetStrategicContextAnalyzer.TeamStrategicContext strategic(
        LeagueFutureCapitalTierPolicy.Tier tier) {
        var posture = new LeagueTeamPostureAnalyzer.TeamPosture(
            "t1", "Team One",
            LeagueCompetitiveTierPolicy.Tier.MIDDLE_TIER,
            LeagueRosterStrengthTierPolicy.Tier.MIDDLE_ROSTER_TIER,
            TeamPosturePolicy.Posture.MIDDLE_OR_MIXED);
        var capital = new LeagueFutureCapitalTierAnalyzer.TeamFutureCapital(
            "t1", "Team One", 100.0, 1, 0, 0, 1, List.of(), tier);
        return new TradeAssetStrategicContextAnalyzer.TeamStrategicContext(TEAM, posture, capital);
    }

    private static TradeAssetPositionalContextAnalyzer.TeamPositionalContext positional(
        LeaguePositionalPressurePolicy.Tier wrTier) {
        return new TradeAssetPositionalContextAnalyzer.TeamPositionalContext(TEAM, Map.of(
            "QB", pressure(LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED),
            "RB", pressure(LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED),
            "WR", pressure(wrTier),
            "TE", pressure(LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED)));
    }

    private static LeaguePositionalPressureAnalyzer.TeamPositionPressure pressure(
        LeaguePositionalPressurePolicy.Tier tier) {
        return new LeaguePositionalPressureAnalyzer.TeamPositionPressure(
            "t1", "Team One", 50.0, 75.0, 3, 3, 0, 0, tier);
    }

    private static TradeAssetAnalyzer.TradeSide side(
        List<TradeAssetAnalyzer.TradePlayer> players,
        List<TradeAssetAnalyzer.TradeDraftPick> picks) {
        double total = players.stream().map(TradeAssetAnalyzer.TradePlayer::value).mapToDouble(Double::doubleValue).sum()
            + picks.stream().map(TradeAssetAnalyzer.TradeDraftPick::value).mapToDouble(Double::doubleValue).sum();
        return new TradeAssetAnalyzer.TradeSide(players, picks, total, players.size(), 0, picks.size(), 0);
    }

    private static TradeAssetAnalyzer.TradePlayer player(String id, String position, double value) {
        return new TradeAssetAnalyzer.TradePlayer(
            id, id, position, "NFL", "t1", "Team One", value, LocalDate.of(2026, 9, 1), false);
    }

    private static TradeAssetAnalyzer.TradeDraftPick pick(String id, double value) {
        return new TradeAssetAnalyzer.TradeDraftPick(
            id, 2027, 1, "2027 1st", "t1", "Team One", "t1", "Team One",
            null, value, LocalDate.of(2026, 9, 1), false);
    }
}
