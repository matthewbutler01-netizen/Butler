package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeStrategicMaterialLossVetoDetectorTest {
    private static final TradeAssetStrategicContextAnalyzer.TeamIdentity TEAM =
        new TradeAssetStrategicContextAnalyzer.TeamIdentity("t1", "Team One");

    @Test
    void lowCapitalExactlyTwentyFivePercentPickLossClears() {
        var result = TradeStrategicMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL, TeamPosturePolicy.Posture.MIDDLE_OR_MIXED),
            positional(Map.of()),
            side(List.of(), List.of(pick("out", 100.0))),
            side(List.of(), List.of(pick("in", 75.0))));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, result.state());
        assertEquals(List.of(), result.reasons());
    }

    @Test
    void lowCapitalGreaterThanTwentyFivePercentPickLossBlocksWithValueEvidence() {
        var result = TradeStrategicMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL, TeamPosturePolicy.Posture.MIDDLE_OR_MIXED),
            positional(Map.of()),
            side(List.of(), List.of(pick("out", 100.0))),
            side(List.of(), List.of(pick("in", 74.0))));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.BLOCKED, result.state());
        assertEquals(1, result.reasons().size());
        var reason = result.reasons().getFirst();
        assertEquals(TradeStrategicMaterialLossVetoDetector.ReasonCode.LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS,
            reason.code());
        assertEquals(null, reason.position());
        assertEquals(100.0, reason.outgoingProtectedValue());
        assertEquals(74.0, reason.incomingProtectedValue());
        assertEquals(0.26, reason.lossFraction(), 0.000001);
    }

    @Test
    void lowCapitalNoPickReturnIsFullMaterialLoss() {
        var result = TradeStrategicMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL, TeamPosturePolicy.Posture.MIDDLE_OR_MIXED),
            positional(Map.of()),
            side(List.of(), List.of(pick("out", 100.0))),
            side(List.of(player("wr-in", "WR", 100.0)), List.of()));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.BLOCKED, result.state());
        assertEquals(1.0, result.reasons().getFirst().lossFraction());
    }

    @Test
    void nonLowCapitalDoesNotVetoPickValueLoss() {
        var result = TradeStrategicMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL, TeamPosturePolicy.Posture.MIDDLE_OR_MIXED),
            positional(Map.of()),
            side(List.of(), List.of(pick("out", 100.0))),
            side(List.of(), List.of()));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, result.state());
    }

    @Test
    void pressuredPositionExactlyTwentyFivePercentLossClears() {
        var result = TradeStrategicMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL, TeamPosturePolicy.Posture.MIDDLE_OR_MIXED),
            positional(Map.of("WR", LeaguePositionalPressurePolicy.Tier.POSITION_PRESSURE)),
            side(List.of(player("wr-out", "WR", 100.0)), List.of()),
            side(List.of(player("wr-in", "WR", 75.0)), List.of()));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, result.state());
    }

    @Test
    void pressuredPositionGreaterThanTwentyFivePercentLossBlocksWithValueEvidence() {
        var result = TradeStrategicMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL, TeamPosturePolicy.Posture.MIDDLE_OR_MIXED),
            positional(Map.of("WR", LeaguePositionalPressurePolicy.Tier.POSITION_PRESSURE)),
            side(List.of(player("wr-out", "WR", 100.0)), List.of()),
            side(List.of(player("wr-in", "WR", 74.0)), List.of()));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.BLOCKED, result.state());
        var reason = result.reasons().getFirst();
        assertEquals(TradeStrategicMaterialLossVetoDetector.ReasonCode.POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS,
            reason.code());
        assertEquals("WR", reason.position());
        assertEquals(100.0, reason.outgoingProtectedValue());
        assertEquals(74.0, reason.incomingProtectedValue());
        assertEquals(0.26, reason.lossFraction(), 0.000001);
    }

    @Test
    void balancedPositionDoesNotVetoMaterialSamePositionLoss() {
        var result = TradeStrategicMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL, TeamPosturePolicy.Posture.MIDDLE_OR_MIXED),
            positional(Map.of("WR", LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED)),
            side(List.of(player("wr-out", "WR", 100.0)), List.of()),
            side(List.of(), List.of()));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, result.state());
    }

    @Test
    void postureAloneCannotCreateMaterialLossVeto() {
        var result = TradeStrategicMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL, TeamPosturePolicy.Posture.REBUILDER),
            positional(Map.of()),
            side(List.of(player("wr-out", "WR", 100.0)), List.of(pick("pick-out", 100.0))),
            side(List.of(), List.of()));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, result.state());
    }

    @Test
    void ordersFutureCapitalThenQbRbWrTeMaterialLossReasons() {
        var result = TradeStrategicMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL, TeamPosturePolicy.Posture.MIDDLE_OR_MIXED),
            positional(Map.of(
                "QB", LeaguePositionalPressurePolicy.Tier.POSITION_PRESSURE,
                "WR", LeaguePositionalPressurePolicy.Tier.POSITION_PRESSURE)),
            side(List.of(player("qb-out", "QB", 100.0), player("wr-out", "WR", 100.0)), List.of(pick("pick-out", 100.0))),
            side(List.of(player("qb-in", "QB", 50.0), player("wr-in", "WR", 50.0)), List.of(pick("pick-in", 50.0))));

        assertEquals(List.of(
                TradeStrategicMaterialLossVetoDetector.ReasonCode.LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS,
                TradeStrategicMaterialLossVetoDetector.ReasonCode.POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS,
                TradeStrategicMaterialLossVetoDetector.ReasonCode.POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS),
            result.reasons().stream().map(TradeStrategicMaterialLossVetoDetector.VetoReason::code).toList());
        assertEquals(java.util.Arrays.asList(null, "QB", "WR"),
            result.reasons().stream().map(TradeStrategicMaterialLossVetoDetector.VetoReason::position).toList());
    }

    @Test
    void missingCorePositionFailsClosed() {
        var incomplete = new TradeAssetPositionalContextAnalyzer.TeamPositionalContext(TEAM, Map.of(
            "QB", pressure("QB", LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED),
            "RB", pressure("RB", LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED),
            "WR", pressure("WR", LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED)));

        assertThrows(IllegalArgumentException.class, () -> TradeStrategicMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL, TeamPosturePolicy.Posture.MIDDLE_OR_MIXED),
            incomplete,
            side(List.of(player("wr-out", "WR", 100.0)), List.of()),
            side(List.of(), List.of())));
    }

    private static TradeAssetStrategicContextAnalyzer.TeamStrategicContext strategic(
        LeagueFutureCapitalTierPolicy.Tier capitalTier,
        TeamPosturePolicy.Posture postureValue) {
        var posture = new LeagueTeamPostureAnalyzer.TeamPosture(
            "t1", "Team One",
            LeagueCompetitiveTierPolicy.Tier.MIDDLE_TIER,
            LeagueRosterStrengthTierPolicy.Tier.MIDDLE_ROSTER_TIER,
            postureValue);
        var capital = new LeagueFutureCapitalTierAnalyzer.TeamFutureCapital(
            "t1", "Team One", 100.0, 1, 0, 0, 1, List.of(), capitalTier);
        return new TradeAssetStrategicContextAnalyzer.TeamStrategicContext(TEAM, posture, capital);
    }

    private static TradeAssetPositionalContextAnalyzer.TeamPositionalContext positional(
        Map<String, LeaguePositionalPressurePolicy.Tier> overrides) {
        return new TradeAssetPositionalContextAnalyzer.TeamPositionalContext(TEAM, Map.of(
            "QB", pressure("QB", overrides.getOrDefault("QB", LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED)),
            "RB", pressure("RB", overrides.getOrDefault("RB", LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED)),
            "WR", pressure("WR", overrides.getOrDefault("WR", LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED)),
            "TE", pressure("TE", overrides.getOrDefault("TE", LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED))));
    }

    private static LeaguePositionalPressureAnalyzer.TeamPositionPressure pressure(
        String position, LeaguePositionalPressurePolicy.Tier tier) {
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
