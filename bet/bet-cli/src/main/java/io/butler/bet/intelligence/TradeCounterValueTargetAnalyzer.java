package io.butler.bet.intelligence;

import java.util.List;
import java.util.Objects;

/**
 * Computes asset-neutral market-value targets that would move an outside-band trade into the
 * existing governed fairness band. This is counter evidence only; it does not select assets,
 * choose an adjustment strategy, emit a recommendation or action, or modify v5 behavior.
 */
public final class TradeCounterValueTargetAnalyzer {
    public static final String POLICY_ID = "trade-counter-value-target-v1-market-fairness-boundary";

    private TradeCounterValueTargetAnalyzer() {}

    public enum Side {
        SIDE_A,
        SIDE_B
    }

    public enum AdjustmentType {
        ADD_TO_LOWER_VALUE_PACKAGE,
        REMOVE_FROM_HIGHER_VALUE_PACKAGE
    }

    public static CounterValueTarget analyze(double sideAValue, double sideBValue) {
        requireFiniteNonNegative(sideAValue, "sideAValue");
        requireFiniteNonNegative(sideBValue, "sideBValue");

        double currentGap = TradeFairnessMeasurementPolicy.symmetricGapPercent(sideAValue, sideBValue);
        var currentFairness = TradeFairnessPolicy.classify(currentGap);
        if (currentFairness == TradeFairnessPolicy.Classification.MARKET_FAIR) {
            return result(currentGap, currentFairness, List.of());
        }
        if (currentFairness != TradeFairnessPolicy.Classification.OUTSIDE_FAIRNESS_BAND) {
            throw new IllegalStateException("complete market values must produce available fairness");
        }

        boolean sideAHigher = sideAValue > sideBValue;
        double higherValue = sideAHigher ? sideAValue : sideBValue;
        double lowerValue = sideAHigher ? sideBValue : sideAValue;
        Side higherSide = sideAHigher ? Side.SIDE_A : Side.SIDE_B;
        Side lowerSide = sideAHigher ? Side.SIDE_B : Side.SIDE_A;

        double fairPercent = TradeFairnessPolicy.MAXIMUM_FAIR_GAP_PERCENT;
        double addTarget = higherValue * ((200.0 - fairPercent) / (200.0 + fairPercent));
        addTarget = ensureFairAfterAddingToLower(higherValue, addTarget);

        double removeTarget = lowerValue == 0.0
            ? 0.0
            : lowerValue * ((200.0 + fairPercent) / (200.0 - fairPercent));
        removeTarget = ensureFairAfterRemovingFromHigher(removeTarget, lowerValue);

        var add = new AdjustmentOption(
            AdjustmentType.ADD_TO_LOWER_VALUE_PACKAGE,
            lowerSide,
            lowerValue,
            addTarget,
            addTarget - lowerValue);
        var remove = new AdjustmentOption(
            AdjustmentType.REMOVE_FROM_HIGHER_VALUE_PACKAGE,
            higherSide,
            higherValue,
            removeTarget,
            higherValue - removeTarget);
        return result(currentGap, currentFairness, List.of(add, remove));
    }

    private static double ensureFairAfterAddingToLower(double higherValue, double lowerTarget) {
        if (!isFair(higherValue, lowerTarget)) {
            lowerTarget = Math.nextUp(lowerTarget);
        }
        if (!isFair(higherValue, lowerTarget)) {
            throw new IllegalStateException("unable to produce governed fair add-value target");
        }
        return lowerTarget;
    }

    private static double ensureFairAfterRemovingFromHigher(double higherTarget, double lowerValue) {
        if (!isFair(higherTarget, lowerValue)) {
            higherTarget = Math.nextDown(higherTarget);
        }
        if (!isFair(higherTarget, lowerValue)) {
            throw new IllegalStateException("unable to produce governed fair remove-value target");
        }
        return higherTarget;
    }

    private static boolean isFair(double sideAValue, double sideBValue) {
        return TradeFairnessPolicy.classify(
            TradeFairnessMeasurementPolicy.symmetricGapPercent(sideAValue, sideBValue))
            == TradeFairnessPolicy.Classification.MARKET_FAIR;
    }

    private static CounterValueTarget result(
        double currentGap,
        TradeFairnessPolicy.Classification currentFairness,
        List<AdjustmentOption> options) {
        return new CounterValueTarget(
            POLICY_ID,
            TradeFairnessMeasurementPolicy.POLICY_ID,
            TradeFairnessPolicy.POLICY_ID,
            currentFairness,
            currentGap,
            options);
    }

    private static void requireFiniteNonNegative(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(field + " must be finite and non-negative");
        }
    }

    public record AdjustmentOption(
        AdjustmentType type,
        Side side,
        double currentValue,
        double targetValue,
        double requiredValueChange) {
        public AdjustmentOption {
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(side, "side must not be null");
            requireFiniteNonNegative(currentValue, "currentValue");
            requireFiniteNonNegative(targetValue, "targetValue");
            if (!Double.isFinite(requiredValueChange) || requiredValueChange <= 0.0) {
                throw new IllegalArgumentException("requiredValueChange must be finite and positive");
            }
            if (type == AdjustmentType.ADD_TO_LOWER_VALUE_PACKAGE && targetValue <= currentValue) {
                throw new IllegalArgumentException("add-value target must exceed current value");
            }
            if (type == AdjustmentType.REMOVE_FROM_HIGHER_VALUE_PACKAGE && targetValue >= currentValue) {
                throw new IllegalArgumentException("remove-value target must be below current value");
            }
        }
    }

    public record CounterValueTarget(
        String policyId,
        String fairnessMeasurementPolicyId,
        String fairnessPolicyId,
        TradeFairnessPolicy.Classification currentFairness,
        double currentGapPercent,
        List<AdjustmentOption> options) {
        public CounterValueTarget {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!TradeFairnessMeasurementPolicy.POLICY_ID.equals(fairnessMeasurementPolicyId)) {
                throw new IllegalArgumentException("unexpected fairnessMeasurementPolicyId");
            }
            if (!TradeFairnessPolicy.POLICY_ID.equals(fairnessPolicyId)) {
                throw new IllegalArgumentException("unexpected fairnessPolicyId");
            }
            Objects.requireNonNull(currentFairness, "currentFairness must not be null");
            if (currentFairness == TradeFairnessPolicy.Classification.UNAVAILABLE) {
                throw new IllegalArgumentException("counter target requires available fairness");
            }
            if (!Double.isFinite(currentGapPercent) || currentGapPercent < 0.0) {
                throw new IllegalArgumentException("currentGapPercent must be finite and non-negative");
            }
            options = List.copyOf(Objects.requireNonNull(options, "options must not be null"));
            if (currentFairness == TradeFairnessPolicy.Classification.MARKET_FAIR && !options.isEmpty()) {
                throw new IllegalArgumentException("market-fair trade cannot carry counter adjustments");
            }
            if (currentFairness == TradeFairnessPolicy.Classification.OUTSIDE_FAIRNESS_BAND) {
                if (options.size() != 2
                    || options.get(0).type() != AdjustmentType.ADD_TO_LOWER_VALUE_PACKAGE
                    || options.get(1).type() != AdjustmentType.REMOVE_FROM_HIGHER_VALUE_PACKAGE
                    || options.get(0).side() == options.get(1).side()) {
                    throw new IllegalArgumentException("outside-band trade requires deterministic counter adjustments");
                }
            }
        }
    }
}
