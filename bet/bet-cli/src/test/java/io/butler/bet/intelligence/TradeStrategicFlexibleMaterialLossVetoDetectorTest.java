package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeStrategicFlexibleMaterialLossVetoDetectorTest {
    private static final TradeAssetStrategicContextAnalyzer.TeamIdentity TEAM =
        new TradeAssetStrategicContextAnalyzer.TeamIdentity("t1", "Team One");

    @Test
    void flexibleMaterialCoverageLossBlocks() {
        var result = TradeStrategicFlexibleMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL),
            positional(),
            TradeFlexibleCoverageMaterialLossAnalyzer.Assessment.materialLoss(100.0, 70.0, 0.30),
            side(List.of(), List.of()),
            side(List.of(), List.of()));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.BLOCKED, result.state());
        assertEquals(1, result.reasons().size());
        var reason = result.reasons().getFirst();
        assertEquals(
            TradeStrategicFlexibleMaterialLossVetoDetector.ReasonCode.FLEXIBLE_PRESSURE_MATERIAL_POST_TRADE_COVERAGE_LOSS,
            reason.code());
        assertEquals(null, reason.position());
        assertEquals(100.0, reason.outgoingProtectedValue());
        assertEquals(70.0, reason.incomingProtectedValue());
        assertEquals(0.30, reason.lossFraction());
    }

    @Test
    void flexibleLossAtTwentyFivePercentClears() {
        var result = TradeStrategicFlexibleMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL),
            positional(),
            TradeFlexibleCoverageMaterialLossAnalyzer.Assessment.withinTolerance(100.0, 75.0, 0.25),
            side(List.of(), List.of()),
            side(List.of(), List.of()));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, result.state());
        assertEquals(List.of(), result.reasons());
    }

    @Test
    void preservesLegacyReasonOrderBeforeFlexibleCoverageReason() {
        var result = TradeStrategicFlexibleMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL),
            positional(),
            TradeFlexibleCoverageMaterialLossAnalyzer.Assessment.materialLoss(100.0, 50.0, 0.50),
            side(List.of(), List.of(pick("out", 100.0))),
            side(List.of(), List.of(pick("in", 50.0))));

        assertEquals(List.of(
                TradeStrategicFlexibleMaterialLossVetoDetector.ReasonCode.LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS,
                TradeStrategicFlexibleMaterialLossVetoDetector.ReasonCode.FLEXIBLE_PRESSURE_MATERIAL_POST_TRADE_COVERAGE_LOSS),
            result.reasons().stream().map(TradeStrategicFlexibleMaterialLossVetoDetector.VetoReason::code).toList());
    }

    @Test
    void unavailableFlexibleEvidenceMustBeGatedBeforeVetoAssessment() {
        assertThrows(IllegalArgumentException.class, () ->
            TradeStrategicFlexibleMaterialLossVetoDetector.assess(
                strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL),
                positional(),
                TradeFlexibleCoverageMaterialLossAnalyzer.Assessment.insufficient("missing flexible evidence"),
                side(List.of(), List.of()),
                side(List.of(), List.of())));
    }

    private static TradeAssetStrategicContextAnalyzer.TeamStrategicContext strategic(
        LeagueFutureCapitalTierPolicy.Tier capitalTier) {
        var posture = new LeagueTeamPostureAnalyzer.TeamPosture(
            "t1", "Team One",
            LeagueCompetitiveTierPolicy.Tier.MIDDLE_TIER,
            LeagueRosterStrengthTierPolicy.Tier.MIDDLE_ROSTER_TIER,
            TeamPosturePolicy.Posture.MIDDLE_OR_MIXED);
        var capital = new LeagueFutureCapitalTierAnalyzer.TeamFutureCapital(
            "t1", "Team One", 100.0, 1, 0, 0, 1, List.of(), capitalTier);
        return new TradeAssetStrategicContextAnalyzer.TeamStrategicContext(TEAM, posture, capital);
    }

    private static TradeAssetPositionalContextAnalyzer.TeamPositionalContext positional() {
        return new TradeAssetPositionalContextAnalyzer.TeamPositionalContext(TEAM, Map.of(
            "QB", pressure("QB"),
            "RB", pressure("RB"),
            "WR", pressure("WR"),
            "TE", pressure("TE")));
    }

    private static LeaguePositionalPressureAnalyzer.TeamPositionPressure pressure(String position) {
        return new LeaguePositionalPressureAnalyzer.TeamPositionPressure(
            "t1", "Team One", 50.0, 75.0, 3, 3, 0, 0,
            LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED);
    }

    private static TradeAssetAnalyzer.TradeSide side(
        List<TradeAssetAnalyzer.TradePlayer> players,
        List<TradeAssetAnalyzer.TradeDraftPick> picks) {
        double total = players.stream().map(TradeAssetAnalyzer.TradePlayer::value).mapToDouble(Double::doubleValue).sum()
            + picks.stream().map(TradeAssetAnalyzer.TradeDraftPick::value).mapToDouble(Double::doubleValue).sum();
        return new TradeAssetAnalyzer.TradeSide(players, picks, total, players.size(), 0, picks.size(), 0);
    }

    private static TradeAssetAnalyzer.TradeDraftPick pick(String id, double value) {
        return new TradeAssetAnalyzer.TradeDraftPick(
            id, 2027, 1, "2027 1st", "t1", "Team One", "t1", "Team One",
            null, value, LocalDate.of(2026, 9, 1), false);
    }
}
