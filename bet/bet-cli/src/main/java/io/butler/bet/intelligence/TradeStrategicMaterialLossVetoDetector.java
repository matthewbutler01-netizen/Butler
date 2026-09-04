package io.butler.bet.intelligence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Versioned strategic veto detector that protects already-weak future-capital and positional areas
 * from material protected-value loss. No weighting, posture-only veto, or market-direction override
 * is applied here.
 */
public final class TradeStrategicMaterialLossVetoDetector {
    public static final String POLICY_ID = "trade-strategic-veto-v2-material-protected-value-loss";
    private static final List<String> CORE_POSITIONS = List.of("QB", "RB", "WR", "TE");
    private static final double LOSS_FRACTION_TOLERANCE = 1e-12;

    private TradeStrategicMaterialLossVetoDetector() {}

    public enum ReasonCode {
        LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS,
        POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS
    }

    public record VetoReason(ReasonCode code,
                             String position,
                             double outgoingProtectedValue,
                             double incomingProtectedValue,
                             double lossFraction) {
        public VetoReason {
            Objects.requireNonNull(code, "code must not be null");
            if (code == ReasonCode.POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS) {
                if (position == null || position.isBlank()) {
                    throw new IllegalArgumentException("position-pressure material-loss veto requires position");
                }
                position = position.trim().toUpperCase(Locale.ROOT);
                if (!CORE_POSITIONS.contains(position)) {
                    throw new IllegalArgumentException("position-pressure material-loss veto requires core position");
                }
            } else if (position != null) {
                throw new IllegalArgumentException("future-capital material-loss veto must not carry position");
            }
            if (!Double.isFinite(outgoingProtectedValue) || outgoingProtectedValue < 0.0) {
                throw new IllegalArgumentException("outgoingProtectedValue must be finite and non-negative");
            }
            if (!Double.isFinite(incomingProtectedValue) || incomingProtectedValue < 0.0) {
                throw new IllegalArgumentException("incomingProtectedValue must be finite and non-negative");
            }
            if (!Double.isFinite(lossFraction) || lossFraction < 0.0 || lossFraction > 1.0) {
                throw new IllegalArgumentException("lossFraction must be finite between 0 and 1");
            }

            var flow = new TradeProtectedValueFlowAnalyzer.ValueFlow(outgoingProtectedValue, incomingProtectedValue);
            double expectedLossFraction = flow.lossFraction();
            if (Math.abs(lossFraction - expectedLossFraction) > LOSS_FRACTION_TOLERANCE) {
                throw new IllegalArgumentException("lossFraction must match outgoing and incoming protected values");
            }
            if (TradeProtectedValueMaterialityPolicy.classify(flow)
                != TradeProtectedValueMaterialityPolicy.Classification.MATERIAL_LOSS) {
                throw new IllegalArgumentException("veto reason requires material protected-value loss");
            }
        }
    }

    public record VetoAssessment(TradeRecommendationVetoPolicy.VetoState state, List<VetoReason> reasons) {
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
        TradeAssetAnalyzer.TradeSide outgoing,
        TradeAssetAnalyzer.TradeSide incoming) {
        Objects.requireNonNull(team, "team must not be null");
        Objects.requireNonNull(positional, "positional must not be null");
        Objects.requireNonNull(outgoing, "outgoing must not be null");
        Objects.requireNonNull(incoming, "incoming must not be null");
        if (!team.identity().equals(positional.identity())) {
            throw new IllegalArgumentException("strategic and positional veto context must reference the same team");
        }

        List<VetoReason> reasons = new ArrayList<>();
        if (team.futureCapital().tier() == LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL) {
            var flow = TradeProtectedValueFlowAnalyzer.draftPickFlow(outgoing, incoming);
            if (TradeProtectedValueMaterialityPolicy.classify(flow)
                == TradeProtectedValueMaterialityPolicy.Classification.MATERIAL_LOSS) {
                reasons.add(reason(ReasonCode.LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS, null, flow));
            }
        }

        for (String position : CORE_POSITIONS) {
            var pressure = positional.positions().get(position);
            if (pressure == null) {
                throw new IllegalArgumentException("positional veto context missing position: " + position);
            }
            if (pressure.tier() != LeaguePositionalPressurePolicy.Tier.POSITION_PRESSURE) continue;
            var flow = TradeProtectedValueFlowAnalyzer.positionFlow(position, outgoing, incoming);
            if (TradeProtectedValueMaterialityPolicy.classify(flow)
                == TradeProtectedValueMaterialityPolicy.Classification.MATERIAL_LOSS) {
                reasons.add(reason(ReasonCode.POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS, position, flow));
            }
        }

        return reasons.isEmpty()
            ? new VetoAssessment(TradeRecommendationVetoPolicy.VetoState.CLEAR, List.of())
            : new VetoAssessment(TradeRecommendationVetoPolicy.VetoState.BLOCKED, reasons);
    }

    private static VetoReason reason(ReasonCode code, String position,
                                     TradeProtectedValueFlowAnalyzer.ValueFlow flow) {
        return new VetoReason(code, position, flow.outgoingValue(), flow.incomingValue(), flow.lossFraction());
    }
}
