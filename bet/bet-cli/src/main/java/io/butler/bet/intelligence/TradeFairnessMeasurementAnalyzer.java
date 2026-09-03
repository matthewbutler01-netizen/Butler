package io.butler.bet.intelligence;

import java.util.Objects;

/**
 * Measures market-value distance for a trade without declaring fairness or producing a recommendation.
 */
public final class TradeFairnessMeasurementAnalyzer {
    public FairnessMeasurement analyze(TradeSupportingEvidenceAnalyzer.TradeEvidencePackage trade) {
        Objects.requireNonNull(trade, "trade must not be null");
        var value = trade.tradeValue();
        if (!value.complete()) {
            return new FairnessMeasurement(
                TradeFairnessMeasurementPolicy.POLICY_ID,
                false,
                null,
                null,
                null,
                "Market-value coverage is incomplete; fairness distance is unavailable.");
        }

        double sideA = value.sideA().totalValue();
        double sideB = value.sideB().totalValue();
        double absoluteGap = Math.abs(sideA - sideB);
        double symmetricGapPercent = TradeFairnessMeasurementPolicy.symmetricGapPercent(sideA, sideB);
        return new FairnessMeasurement(
            TradeFairnessMeasurementPolicy.POLICY_ID,
            true,
            absoluteGap,
            symmetricGapPercent,
            sideA - sideB,
            "Measurement only; no fairness tolerance, winner, accept/reject action, or recommendation is governed.");
    }

    public record FairnessMeasurement(String policyId,
                                      boolean available,
                                      Double absoluteGap,
                                      Double symmetricGapPercent,
                                      Double signedValueDifference,
                                      String interpretationBoundary) {
        public FairnessMeasurement {
            Objects.requireNonNull(policyId, "policyId must not be null");
            Objects.requireNonNull(interpretationBoundary, "interpretationBoundary must not be null");
            if (available && (absoluteGap == null || symmetricGapPercent == null || signedValueDifference == null)) {
                throw new IllegalArgumentException("available measurement requires all market-value gap fields");
            }
            if (!available && (absoluteGap != null || symmetricGapPercent != null || signedValueDifference != null)) {
                throw new IllegalArgumentException("unavailable measurement must not expose partial gap fields");
            }
        }
    }
}
