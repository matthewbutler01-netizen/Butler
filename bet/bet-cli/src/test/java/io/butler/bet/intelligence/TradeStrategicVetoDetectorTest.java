package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeStrategicVetoDetectorTest {
    private static final TradeAssetStrategicContextAnalyzer.TeamIdentity TEAM =
        new TradeAssetStrategicContextAnalyzer.TeamIdentity("t1", "Team One");

    @Test
    void blocksLowFutureCapitalTeamSendingPicksWithoutPickReturn() {
        var result = TradeStrategicVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL),
            positional(LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED),
            side(List.of(), List.of(pick("p1"))),
            side(List.of(player("wr2", "WR")), List.of()));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.BLOCKED, result.state());
        assertEquals(List.of(new TradeStrategicVetoDetector.VetoReason(
            TradeStrategicVetoDetector.ReasonCode.LOW_FUTURE_CAPITAL_OUTGOING_PICKS_WITHOUT_PICK_RETURN, null)),
            result.reasons());
    }

    @Test
    void lowFutureCapitalTeamReceivingPickDoesNotTriggerFutureCapitalVeto() {
        var result = TradeStrategicVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL),
            positional(LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED),
            side(List.of(), List.of(pick("p1"))),
            side(List.of(), List.of(pick("p2"))));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, result.state());
        assertEquals(List.of(), result.reasons());
    }

    @Test
    void doesNotInventFutureCapitalVetoOutsideLowTier() {
        var result = TradeStrategicVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL),
            positional(LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED),
            side(List.of(), List.of(pick("p1"))),
            side(List.of(player("wr2", "WR")), List.of()));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, result.state());
        assertEquals(List.of(), result.reasons());
    }

    @Test
    void postureAloneDoesNotCreateStrategicVeto() {
        var contender = TradeStrategicVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL, TeamPosturePolicy.Posture.CONTENDER),
            positional(LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED),
            side(List.of(player("wr1", "WR")), List.of()),
            side(List.of(player("rb2", "RB")), List.of()));
        var rebuilder = TradeStrategicVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL, TeamPosturePolicy.Posture.REBUILDER),
            positional(LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED),
            side(List.of(player("wr1", "WR")), List.of()),
            side(List.of(player("rb2", "RB")), List.of()));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, contender.state());
        assertEquals(List.of(), contender.reasons());
        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, rebuilder.state());
        assertEquals(List.of(), rebuilder.reasons());
    }

    @Test
    void blocksSendingFromPressuredPositionWithoutSamePositionReturn() {
        var result = TradeStrategicVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL),
            positional(LeaguePositionalPressurePolicy.Tier.POSITION_PRESSURE),
            side(List.of(player("wr1", "WR")), List.of()),
            side(List.of(player("rb2", "RB")), List.of()));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.BLOCKED, result.state());
        assertEquals(List.of(new TradeStrategicVetoDetector.VetoReason(
            TradeStrategicVetoDetector.ReasonCode.POSITION_PRESSURE_OUTGOING_WITHOUT_SAME_POSITION_RETURN, "WR")),
            result.reasons());
    }

    @Test
    void samePositionReturnClearsPressureVeto() {
        var result = TradeStrategicVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL),
            positional(LeaguePositionalPressurePolicy.Tier.POSITION_PRESSURE),
            side(List.of(player("wr1", "WR")), List.of()),
            side(List.of(player("wr2", "WR")), List.of()));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, result.state());
    }

    @Test
    void balancedPositionDoesNotCreateVeto() {
        var result = TradeStrategicVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL),
            positional(LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED),
            side(List.of(player("wr1", "WR")), List.of()),
            side(List.of(player("rb2", "RB")), List.of()));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, result.state());
    }

    @Test
    void ordersMultipleVetoReasonsDeterministically() {
        var result = TradeStrategicVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL),
            positional(Map.of(
                "QB", LeaguePositionalPressurePolicy.Tier.POSITION_PRESSURE,
                "RB", LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED,
                "WR", LeaguePositionalPressurePolicy.Tier.POSITION_PRESSURE,
                "TE", LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED)),
            side(List.of(player("qb1", "QB"), player("wr1", "WR")), List.of(pick("p1"))),
            side(List.of(player("rb2", "RB")), List.of()));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.BLOCKED, result.state());
        assertEquals(List.of(
            new TradeStrategicVetoDetector.VetoReason(
                TradeStrategicVetoDetector.ReasonCode.LOW_FUTURE_CAPITAL_OUTGOING_PICKS_WITHOUT_PICK_RETURN, null),
            new TradeStrategicVetoDetector.VetoReason(
                TradeStrategicVetoDetector.ReasonCode.POSITION_PRESSURE_OUTGOING_WITHOUT_SAME_POSITION_RETURN, "QB"),
            new TradeStrategicVetoDetector.VetoReason(
                TradeStrategicVetoDetector.ReasonCode.POSITION_PRESSURE_OUTGOING_WITHOUT_SAME_POSITION_RETURN, "WR")),
            result.reasons());
    }

    private static TradeAssetStrategicContextAnalyzer.TeamStrategicContext strategic(
        LeagueFutureCapitalTierPolicy.Tier capitalTier) {
        return strategic(capitalTier, TeamPosturePolicy.Posture.MIDDLE_OR_MIXED);
    }

    private static TradeAssetStrategicContextAnalyzer.TeamStrategicContext strategic(
        LeagueFutureCapitalTierPolicy.Tier capitalTier,
        TeamPosturePolicy.Posture teamPosture) {
        LeagueCompetitiveTierPolicy.Tier competitive = switch (teamPosture) {
            case CONTENDER -> LeagueCompetitiveTierPolicy.Tier.FRONT_TIER;
            case REBUILDER -> LeagueCompetitiveTierPolicy.Tier.BACK_TIER;
            case MIDDLE_OR_MIXED -> LeagueCompetitiveTierPolicy.Tier.MIDDLE_TIER;
            case INSUFFICIENT_EVIDENCE -> LeagueCompetitiveTierPolicy.Tier.INSUFFICIENT_EVIDENCE;
        };
        LeagueRosterStrengthTierPolicy.Tier roster = switch (teamPosture) {
            case CONTENDER -> LeagueRosterStrengthTierPolicy.Tier.FRONT_ROSTER_TIER;
            case REBUILDER -> LeagueRosterStrengthTierPolicy.Tier.BACK_ROSTER_TIER;
            case MIDDLE_OR_MIXED -> LeagueRosterStrengthTierPolicy.Tier.MIDDLE_ROSTER_TIER;
            case INSUFFICIENT_EVIDENCE -> LeagueRosterStrengthTierPolicy.Tier.INSUFFICIENT_EVIDENCE;
        };
        var posture = new LeagueTeamPostureAnalyzer.TeamPosture(
            "t1", "Team One", competitive, roster, teamPosture);
        var capital = new LeagueFutureCapitalTierAnalyzer.TeamFutureCapital(
            "t1", "Team One", 100.0, 1, 0, 0, 1, List.of(), capitalTier);
        return new TradeAssetStrategicContextAnalyzer.TeamStrategicContext(TEAM, posture, capital);
    }

    private static TradeAssetPositionalContextAnalyzer.TeamPositionalContext positional(
        LeaguePositionalPressurePolicy.Tier wrTier) {
        return positional(Map.of(
            "QB", LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED,
            "RB", LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED,
            "WR", wrTier,
            "TE", LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED));
    }

    private static TradeAssetPositionalContextAnalyzer.TeamPositionalContext positional(
        Map<String, LeaguePositionalPressurePolicy.Tier> tiers) {
        return new TradeAssetPositionalContextAnalyzer.TeamPositionalContext(TEAM, Map.of(
            "QB", pressure("QB", tiers.get("QB")),
            "RB", pressure("RB", tiers.get("RB")),
            "WR", pressure("WR", tiers.get("WR")),
            "TE", pressure("TE", tiers.get("TE"))));
    }

    private static LeaguePositionalPressureAnalyzer.TeamPositionPressure pressure(
        String position, LeaguePositionalPressurePolicy.Tier tier) {
        return new LeaguePositionalPressureAnalyzer.TeamPositionPressure(
            "t1", "Team One", 50.0, 75.0, 3, 3, 0, 0, tier);
    }

    private static TradeAssetAnalyzer.TradeSide side(
        List<TradeAssetAnalyzer.TradePlayer> players,
        List<TradeAssetAnalyzer.TradeDraftPick> picks) {
        return new TradeAssetAnalyzer.TradeSide(players, picks, 100.0,
            players.size(), 0, picks.size(), 0);
    }

    private static TradeAssetAnalyzer.TradePlayer player(String id, String position) {
        return new TradeAssetAnalyzer.TradePlayer(
            id, id, position, "NFL", "t1", "Team One", 100.0, LocalDate.of(2026, 9, 1), false);
    }

    private static TradeAssetAnalyzer.TradeDraftPick pick(String id) {
        return new TradeAssetAnalyzer.TradeDraftPick(
            id, 2027, 1, "2027 1st", "t1", "Team One", "t1", "Team One",
            null, 100.0, LocalDate.of(2026, 9, 1), false);
    }
}
