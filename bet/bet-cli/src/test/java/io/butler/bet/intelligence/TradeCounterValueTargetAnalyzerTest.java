package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeCounterValueTargetAnalyzerTest {
    @Test
    void outsideBandTradeReturnsTwoAssetNeutralFairnessTargets() {
        var result = TradeCounterValueTargetAnalyzer.analyze(105.0, 95.0);

        assertEquals(TradeFairnessPolicy.Classification.OUTSIDE_FAIRNESS_BAND, result.currentFairness());
        assertEquals(10.0, result.currentGapPercent());
        assertEquals(2, result.options().size());

        var add = result.options().get(0);
        assertEquals(TradeCounterValueTargetAnalyzer.AdjustmentType.ADD_TO_LOWER_VALUE_PACKAGE, add.type());
        assertEquals(TradeCounterValueTargetAnalyzer.Side.SIDE_B, add.side());
        assertEquals(95.0, add.currentValue());
        assertTrue(add.targetValue() > add.currentValue());
        assertEquals(add.targetValue() - add.currentValue(), add.requiredValueChange());
        assertFair(105.0, add.targetValue());

        var remove = result.options().get(1);
        assertEquals(TradeCounterValueTargetAnalyzer.AdjustmentType.REMOVE_FROM_HIGHER_VALUE_PACKAGE, remove.type());
        assertEquals(TradeCounterValueTargetAnalyzer.Side.SIDE_A, remove.side());
        assertEquals(105.0, remove.currentValue());
        assertTrue(remove.targetValue() < remove.currentValue());
        assertEquals(remove.currentValue() - remove.targetValue(), remove.requiredValueChange());
        assertFair(remove.targetValue(), 95.0);
    }

    @Test
    void mirroredTradeMirrorsAdjustmentSidesAndMagnitudes() {
        var forward = TradeCounterValueTargetAnalyzer.analyze(105.0, 95.0);
        var mirrored = TradeCounterValueTargetAnalyzer.analyze(95.0, 105.0);

        assertEquals(TradeCounterValueTargetAnalyzer.Side.SIDE_A, mirrored.options().get(0).side());
        assertEquals(TradeCounterValueTargetAnalyzer.Side.SIDE_B, mirrored.options().get(1).side());
        assertEquals(forward.options().get(0).requiredValueChange(),
            mirrored.options().get(0).requiredValueChange());
        assertEquals(forward.options().get(1).requiredValueChange(),
            mirrored.options().get(1).requiredValueChange());
    }

    @Test
    void marketFairTradeNeedsNoCounterAdjustment() {
        var result = TradeCounterValueTargetAnalyzer.analyze(102.0, 100.0);

        assertEquals(TradeFairnessPolicy.Classification.MARKET_FAIR, result.currentFairness());
        assertTrue(result.options().isEmpty());
    }

    @Test
    void exactFivePercentBoundaryNeedsNoCounterAdjustment() {
        var result = TradeCounterValueTargetAnalyzer.analyze(205.0, 195.0);

        assertEquals(5.0, result.currentGapPercent());
        assertEquals(TradeFairnessPolicy.Classification.MARKET_FAIR, result.currentFairness());
        assertTrue(result.options().isEmpty());
    }

    @Test
    void bothZeroIsMarketFairAndNeedsNoCounterAdjustment() {
        var result = TradeCounterValueTargetAnalyzer.analyze(0.0, 0.0);

        assertEquals(0.0, result.currentGapPercent());
        assertEquals(TradeFairnessPolicy.Classification.MARKET_FAIR, result.currentFairness());
        assertTrue(result.options().isEmpty());
    }

    @Test
    void zeroLowerPackageStillProducesTwoFairTargets() {
        var result = TradeCounterValueTargetAnalyzer.analyze(100.0, 0.0);
        var add = result.options().get(0);
        var remove = result.options().get(1);

        assertEquals(TradeCounterValueTargetAnalyzer.Side.SIDE_B, add.side());
        assertFair(100.0, add.targetValue());
        assertEquals(TradeCounterValueTargetAnalyzer.Side.SIDE_A, remove.side());
        assertEquals(0.0, remove.targetValue());
        assertEquals(100.0, remove.requiredValueChange());
        assertFair(remove.targetValue(), 0.0);
    }

    @Test
    void returnedTargetsCarryExistingFairnessProvenance() {
        var result = TradeCounterValueTargetAnalyzer.analyze(105.0, 95.0);

        assertEquals("trade-counter-value-target-v1-market-fairness-boundary", result.policyId());
        assertEquals(TradeFairnessMeasurementPolicy.POLICY_ID, result.fairnessMeasurementPolicyId());
        assertEquals(TradeFairnessPolicy.POLICY_ID, result.fairnessPolicyId());
    }

    @Test
    void rejectsInvalidPackageValues() {
        assertThrows(IllegalArgumentException.class,
            () -> TradeCounterValueTargetAnalyzer.analyze(-1.0, 100.0));
        assertThrows(IllegalArgumentException.class,
            () -> TradeCounterValueTargetAnalyzer.analyze(Double.NaN, 100.0));
        assertThrows(IllegalArgumentException.class,
            () -> TradeCounterValueTargetAnalyzer.analyze(100.0, Double.POSITIVE_INFINITY));
    }

    private static void assertFair(double sideAValue, double sideBValue) {
        double gap = TradeFairnessMeasurementPolicy.symmetricGapPercent(sideAValue, sideBValue);
        assertTrue(gap <= TradeFairnessPolicy.MAXIMUM_FAIR_GAP_PERCENT);
        assertEquals(TradeFairnessPolicy.Classification.MARKET_FAIR, TradeFairnessPolicy.classify(gap));
    }
}
