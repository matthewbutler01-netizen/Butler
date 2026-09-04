package io.butler.bet.intelligence;

import java.util.Objects;

/**
 * Governs whether protected-value loss is material for strategic veto purposes.
 * A loss greater than 25% of outgoing protected value is material; exactly 25% is allowed.
 */
public final class TradeProtectedValueMaterialityPolicy {
    public static final String POLICY_ID = "trade-protected-value-materiality-v1-25-percent-loss";
    public static final double MAX_ALLOWED_LOSS_FRACTION = 0.25;

    private TradeProtectedValueMaterialityPolicy() {}

    public enum Classification {
        WITHIN_TOLERANCE,
        MATERIAL_LOSS
    }

    public static Classification classify(TradeProtectedValueFlowAnalyzer.ValueFlow flow) {
        Objects.requireNonNull(flow, "flow must not be null");
        return Double.compare(flow.lossFraction(), MAX_ALLOWED_LOSS_FRACTION) > 0
            ? Classification.MATERIAL_LOSS
            : Classification.WITHIN_TOLERANCE;
    }
}
