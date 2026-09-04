package io.butler.bet.intelligence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Versioned strategic veto detector that preserves v2 protected-value reasons and adds governed
 * legal post-trade FLEX/SUPERFLEX coverage loss. No weighting, side flipping, or score blending
 * is applied. Reasons are ordered future capital, QB/RB/WR/TE, then flexible coverage.
 */
public final class TradeStrategicFlexibleMaterialLossVetoDetector {
    public static final String POLICY_ID =
        "trade-strategic-veto-v3-material-protected-value-plus-flexible-coverage-loss";
    private static final List<String> CORE_POSITIONS = List.of("QB", "RB", "WR", "TE");
    private static final double LOSS_FRACTION_TOLERANCE = 1e-12;

    private TradeStrategicFlexibleMaterialLossVetoDetector() {}

    public enum ReasonCode {
        LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS,
        POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS,
        FLEXIBLE_PRESSURE_MATERIAL_POST_TRADE_COVERAGE_LOSS
    }

    public record VetoReason(
        ReasonCode code,
        String position,
        double outgoingProtectedValue,
        double incomingProtectedValue,
        double lossFraction) {
        public VetoReason {
            Objects.requireNonNull(code, "code must not be null");
            if (code == ReasonCode.POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS) {
                if (position == null || position.isBlank()) {
                    throw new IllegalArgumentException("position material-loss veto requires position");
                }
                position = position.trim().toUpperCase(Locale.ROOT);
                if (!CORE_POSITIONS.contains(position)) {
                    throw new IllegalArgumentException("position material-loss veto requires core position");
                }
            } else if (position != null) {
                throw new IllegalArgumentException("non-position material-loss veto must not carry position");
            }
            if (!Double.isFinite(outgoingProtectedValue) || outgoingProtectedValue < 0.0
                || !Double.isFinite(incomingProtectedValue) || incomingProtectedValue < 0.0
                || !Double.isFinite(lossFraction) || lossFraction < 0.0 || lossFraction > 1.0) {
                throw new IllegalArgumentException("invalid material-loss veto values");
            }
            var flow = new TradeProtectedValueFlowAnalyzer.ValueFlow(
                outgoingProtectedValue, incomingProtectedValue);
            if (Math.abs(flow.lossFraction() - lossFraction) > LOSS_FRACTION_TOLERANCE) {
                throw new IllegalArgumentException("lossFraction must match protected values");
            }
            if (TradeProtectedValueMaterialityPolicy.classify(flow)
                != TradeProtectedValueMaterialityPolicy.Classification.MATERIAL_LOSS) {
                throw new IllegalArgumentException("veto reason requires material loss");
            }
        }
    }

    public record VetoAssessment(
        TradeRecommendationVetoPolicy.VetoState state,
        List<VetoReason> reasons) {
        public VetoAssessment {
            Objects.requireNonNull(state, "state must not be null");
            reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons must not be null"));
            if (state == TradeRecommendationVetoPolicy.VetoState.CLEAR && !reasons.isEmpty()) {
                throw new IllegalArgumentException("clear veto assessment cannot contain reasons");
            }
            if (state == TradeRecommendationVetoPolicy.VetoState.BLOCKED && reasons.isEmpty()) {
                throw new IllegalArgumentException("blocked veto assessment requires reasons");
            }
        }
    }

    public static VetoAssessment assess(
        TradeAssetStrategicContextAnalyzer.TeamStrategicContext team,
        TradeAssetPositionalContextAnalyzer.TeamPositionalContext positional,
        TradeFlexibleCoverageMaterialLossAnalyzer.Assessment flexible,
        TradeAssetAnalyzer.TradeSide outgoing,
        TradeAssetAnalyzer.TradeSide incoming) {
        Objects.requireNonNull(team, "team must not be null");
        Objects.requireNonNull(positional, "positional must not be null");
        Objects.requireNonNull(flexible, "flexible must not be null");
        Objects.requireNonNull(outgoing, "outgoing must not be null");
        Objects.requireNonNull(incoming, "incoming must not be null");
        if (!flexible.available()) {
            throw new IllegalArgumentException(
                "flexible material-loss evidence must be available before veto assessment");
        }

        var legacy = TradeStrategicMaterialLossVetoDetector.assess(
            team, positional, outgoing, incoming);
        List<VetoReason> reasons = new ArrayList<>();
        for (var reason : legacy.reasons()) {
            ReasonCode code = switch (reason.code()) {
                case LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS ->
                    ReasonCode.LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS;
                case POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS ->
                    ReasonCode.POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS;
            };
            reasons.add(new VetoReason(
                code,
                reason.position(),
                reason.outgoingProtectedValue(),
                reason.incomingProtectedValue(),
                reason.lossFraction()));
        }

        if (flexible.materialLoss()) {
            reasons.add(new VetoReason(
                ReasonCode.FLEXIBLE_PRESSURE_MATERIAL_POST_TRADE_COVERAGE_LOSS,
                null,
                flexible.preTradeCoverageValue(),
                flexible.postTradeCoverageValue(),
                flexible.lossFraction()));
        }

        return reasons.isEmpty()
            ? new VetoAssessment(TradeRecommendationVetoPolicy.VetoState.CLEAR, List.of())
            : new VetoAssessment(TradeRecommendationVetoPolicy.VetoState.BLOCKED, reasons);
    }
}
