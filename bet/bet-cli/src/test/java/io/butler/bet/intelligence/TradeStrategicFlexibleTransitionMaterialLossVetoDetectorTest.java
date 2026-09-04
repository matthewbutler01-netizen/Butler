package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeStrategicFlexibleTransitionMaterialLossVetoDetectorTest {
    private static final TradeAssetStrategicContextAnalyzer.TeamIdentity TEAM =
        new TradeAssetStrategicContextAnalyzer.TeamIdentity("t1", "Team One");

    @Test
    void materialTransitionToFlexiblePressureBlocks() {
        var result = TradeStrategicFlexibleTransitionMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL),
            positional(),
            TradeFlexibleCoverageMaterialLossAnalyzer.Assessment.notProtected(),
            transition(
                TradeFlexiblePressureTransitionAnalyzer.AssessmentState.MATERIAL_TRANSITION_TO_PRESSURE,
                LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_BALANCED,
                LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE,
                100.0, 60.0, 0.40),
            side(List.of(), List.of()),
            side(List.of(), List.of()));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.BLOCKED, result.state());
        assertEquals(1, result.reasons().size());
        var reason = result.reasons().getFirst();
        assertEquals(
            TradeStrategicFlexibleTransitionMaterialLossVetoDetector.ReasonCode
                .FLEXIBLE_MATERIAL_LOSS_TRANSITION_TO_PRESSURE,
            reason.code());
        assertEquals(null, reason.position());
        assertEquals(100.0, reason.outgoingProtectedValue());
        assertEquals(60.0, reason.incomingProtectedValue());
        assertEquals(0.40, reason.lossFraction());
    }

    @Test
    void transitionAtExactlyTwentyFivePercentDoesNotBlock() {
        var result = TradeStrategicFlexibleTransitionMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL),
            positional(),
            TradeFlexibleCoverageMaterialLossAnalyzer.Assessment.notProtected(),
            transition(
                TradeFlexiblePressureTransitionAnalyzer.AssessmentState.TRANSITION_WITHIN_TOLERANCE,
                LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_BALANCED,
                LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE,
                100.0, 75.0, 0.25),
            side(List.of(), List.of()),
            side(List.of(), List.of()));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, result.state());
        assertEquals(List.of(), result.reasons());
    }

    @Test
    void preservesExistingReasonOrderBeforeTransitionReason() {
        var result = TradeStrategicFlexibleTransitionMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL),
            positional(),
            TradeFlexibleCoverageMaterialLossAnalyzer.Assessment.notProtected(),
            transition(
                TradeFlexiblePressureTransitionAnalyzer.AssessmentState.MATERIAL_TRANSITION_TO_PRESSURE,
                LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_BALANCED,
                LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE,
                100.0, 60.0, 0.40),
            side(List.of(), List.of(pick("out", 100.0))),
            side(List.of(), List.of(pick("in", 50.0))));

        assertEquals(List.of(
                TradeStrategicFlexibleTransitionMaterialLossVetoDetector.ReasonCode
                    .LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS,
                TradeStrategicFlexibleTransitionMaterialLossVetoDetector.ReasonCode
                    .FLEXIBLE_MATERIAL_LOSS_TRANSITION_TO_PRESSURE),
            result.reasons().stream()
                .map(TradeStrategicFlexibleTransitionMaterialLossVetoDetector.VetoReason::code)
                .toList());
    }

    @Test
    void preExistingFlexiblePressureUsesExistingReasonWithoutTransitionDuplicate() {
        var result = TradeStrategicFlexibleTransitionMaterialLossVetoDetector.assess(
            strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL),
            positional(),
            TradeFlexibleCoverageMaterialLossAnalyzer.Assessment.materialLoss(100.0, 60.0, 0.40),
            transition(
                TradeFlexiblePressureTransitionAnalyzer.AssessmentState.NO_TRANSITION,
                LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE,
                LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE,
                100.0, 60.0, 0.40),
            side(List.of(), List.of()),
            side(List.of(), List.of()));

        assertEquals(TradeRecommendationVetoPolicy.VetoState.BLOCKED, result.state());
        assertEquals(1, result.reasons().size());
        assertEquals(
            TradeStrategicFlexibleTransitionMaterialLossVetoDetector.ReasonCode
                .FLEXIBLE_PRESSURE_MATERIAL_POST_TRADE_COVERAGE_LOSS,
            result.reasons().getFirst().code());
    }

    @Test
    void unavailableTransitionEvidenceMustBeGatedBeforeVetoAssessment() {
        assertThrows(IllegalArgumentException.class, () ->
            TradeStrategicFlexibleTransitionMaterialLossVetoDetector.assess(
                strategic(LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL),
                positional(),
                TradeFlexibleCoverageMaterialLossAnalyzer.Assessment.notProtected(),
                TradeFlexiblePressureTransitionAnalyzer.Assessment.insufficient("missing transition evidence"),
                side(List.of(), List.of()),
                side(List.of(), List.of())));
    }

    @Test
    void v5PolicyRemainsMarketFirstAndDowngradeOnly() {
        var evidence = new TradeRecommendationFlexibleTransitionMaterialLossPolicy.EvidenceGate(
            true, true, true, true);

        assertEquals(TradeRecommendationPolicy.Recommendation.SIDE_A_PACKAGE_PREFERRED,
            TradeRecommendationFlexibleTransitionMaterialLossPolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
                evidence,
                TradeRecommendationVetoPolicy.VetoState.CLEAR));
        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD,
            TradeRecommendationFlexibleTransitionMaterialLossPolicy.classify(
                TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
                evidence,
                TradeRecommendationVetoPolicy.VetoState.BLOCKED));
        assertEquals(TradeRecommendationPolicy.Recommendation.HOLD,
            TradeRecommendationFlexibleTransitionMaterialLossPolicy.classify(
                TradeMarketEdgePolicy.Direction.MARKET_FAIR,
                evidence,
                TradeRecommendationVetoPolicy.VetoState.BLOCKED));
        assertEquals(TradeRecommendationPolicy.Recommendation.INCONCLUSIVE,
            TradeRecommendationFlexibleTransitionMaterialLossPolicy.classify(
                TradeMarketEdgePolicy.Direction.UNAVAILABLE,
                evidence,
                TradeRecommendationVetoPolicy.VetoState.CLEAR));
    }

    private static TradeFlexiblePressureTransitionAnalyzer.Assessment transition(
        TradeFlexiblePressureTransitionAnalyzer.AssessmentState state,
        LeagueFlexibleSlotPressurePolicy.Tier preTier,
        LeagueFlexibleSlotPressurePolicy.Tier postTier,
        double before,
        double after,
        double lossFraction) {
        return new TradeFlexiblePressureTransitionAnalyzer.Assessment(
            TradeFlexiblePressureTransitionAnalyzer.POLICY_ID,
            TradeFlexiblePostTradeDepthAnalyzer.POLICY_ID,
            TradeProtectedValueMaterialityPolicy.POLICY_ID,
            state,
            null,
            preTier,
            postTier,
            before,
            after,
            lossFraction);
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
