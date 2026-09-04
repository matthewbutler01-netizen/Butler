package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeProtectedValueMaterialityPolicyTest {
    @Test
    void exactlyTwentyFivePercentLossIsWithinTolerance() {
        assertEquals(TradeProtectedValueMaterialityPolicy.Classification.WITHIN_TOLERANCE,
            TradeProtectedValueMaterialityPolicy.classify(
                new TradeProtectedValueFlowAnalyzer.ValueFlow(100.0, 75.0)));
    }

    @Test
    void lossGreaterThanTwentyFivePercentIsMaterial() {
        assertEquals(TradeProtectedValueMaterialityPolicy.Classification.MATERIAL_LOSS,
            TradeProtectedValueMaterialityPolicy.classify(
                new TradeProtectedValueFlowAnalyzer.ValueFlow(100.0, 74.0)));
    }

    @Test
    void smallerLossUpgradeAndZeroOutgoingAreWithinTolerance() {
        assertEquals(TradeProtectedValueMaterialityPolicy.Classification.WITHIN_TOLERANCE,
            TradeProtectedValueMaterialityPolicy.classify(
                new TradeProtectedValueFlowAnalyzer.ValueFlow(100.0, 80.0)));
        assertEquals(TradeProtectedValueMaterialityPolicy.Classification.WITHIN_TOLERANCE,
            TradeProtectedValueMaterialityPolicy.classify(
                new TradeProtectedValueFlowAnalyzer.ValueFlow(100.0, 125.0)));
        assertEquals(TradeProtectedValueMaterialityPolicy.Classification.WITHIN_TOLERANCE,
            TradeProtectedValueMaterialityPolicy.classify(
                new TradeProtectedValueFlowAnalyzer.ValueFlow(0.0, 25.0)));
    }

    @Test
    void locksPolicyContract() {
        assertEquals("trade-protected-value-materiality-v1-25-percent-loss",
            TradeProtectedValueMaterialityPolicy.POLICY_ID);
        assertEquals(0.25, TradeProtectedValueMaterialityPolicy.MAX_ALLOWED_LOSS_FRACTION);
    }

    @Test
    void rejectsNullFlow() {
        assertThrows(NullPointerException.class, () -> TradeProtectedValueMaterialityPolicy.classify(null));
    }
}
