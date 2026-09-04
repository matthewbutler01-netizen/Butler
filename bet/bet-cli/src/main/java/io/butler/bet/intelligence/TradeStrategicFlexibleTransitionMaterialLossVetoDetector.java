package io.butler.bet.intelligence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Versioned strategic veto detector that preserves all v4 material-loss reasons and adds one
 * governed reason when a selected team newly enters FLEXIBLE_PRESSURE with more than 25% legal
 * flexible coverage loss. Reasons are ordered future capital, QB/RB/WR/TE, existing flexible
 * pressure loss, then transition-to-pressure loss.
 */
public final class TradeStrategicFlexibleTransitionMaterialLossVetoDetector {
    public static final String POLICY_ID =
        "trade-strategic-veto-v4-material-protected-value-plus-flexible-transition-loss";
    private static final List<String> CORE_POSITIONS = List.of("QB", "RB", "WR", "TE");
    private static final double LOSS_FRACTION_TOLERANCE = 1e-12;

    private TradeStrategicFlexibleTransitionMaterialLossVetoDetector() {}

    public enum ReasonCode {
        LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS,
        POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS,
        FLEXIBLE_PRESSURE_MATERIAL_POST_TRADE_COVERAGE_LOSS,
        FLEXIBLE_MATERIAL_LOSS_TRANSITION_TO_PRESSURE
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
        TradeFlexiblePressureTransitionAnalyzer.Assessment transition,
        TradeAssetAnalyzer.TradeSide outgoing,
        TradeAssetAnalyzer.TradeSide incoming) {
        Objects.requireNonNull(team, "team must not be null");
        Objects.requireNonNull(positional, "positional must not be null");
        Objects.requireNonNull(flexible, "flexible must not be null");
        Objects.requireNonNull(transition, "transition must not be null");
        Objects.requireNonNull(outgoing, "outgoing must not be null");
        Objects.requireNonNull(incoming, "incoming must not be null");
        if (!flexible.available()) {
            throw new IllegalArgumentException(
                "flexible material-loss evidence must be available before veto assessment");
        }
        if (!transition.available()) {
            throw new IllegalArgumentException(
                "flexible transition evidence must be available before veto assessment");
        }
        if (flexible.materialLoss() && transition.materialTransitionToPressure()) {
            throw new IllegalArgumentException(
                "existing flexible-pressure loss and new transition-to-pressure loss cannot both apply");
        }

        var legacy = TradeStrategicFlexibleMaterialLossVetoDetector.assess(
            team, positional, flexible, outgoing, incoming);
        List<VetoReason> reasons = new ArrayList<>();
        for (var reason : legacy.reasons()) {
            ReasonCode code = switch (reason.code()) {
                case LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS ->
                    ReasonCode.LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS;
                case POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS ->
                    ReasonCode.POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS;
                case FLEXIBLE_PRESSURE_MATERIAL_POST_TRADE_COVERAGE_LOSS ->
                    ReasonCode.FLEXIBLE_PRESSURE_MATERIAL_POST_TRADE_COVERAGE_LOSS;
            };
            reasons.add(new VetoReason(
                code,
                reason.position(),
                reason.outgoingProtectedValue(),
                reason.incomingProtectedValue(),
                reason.lossFraction()));
        }

        if (transition.materialTransitionToPressure()) {
            reasons.add(new VetoReason(
                ReasonCode.FLEXIBLE_MATERIAL_LOSS_TRANSITION_TO_PRESSURE,
                null,
                transition.preTradeCoverageValue(),
                transition.postTradeCoverageValue(),
                transition.lossFraction()));
        }

        return reasons.isEmpty()
            ? new VetoAssessment(TradeRecommendationVetoPolicy.VetoState.CLEAR, List.of())
            : new VetoAssessment(TradeRecommendationVetoPolicy.VetoState.BLOCKED, reasons);
    }
}
