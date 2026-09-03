package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeFairnessPolicyTest {
    @Test
    void classifiesFivePercentAndBelowAsMarketFair() {
        assertEquals(TradeFairnessPolicy.Classification.MARKET_FAIR, TradeFairnessPolicy.classify(0.0));
        assertEquals(TradeFairnessPolicy.Classification.MARKET_FAIR, TradeFairnessPolicy.classify(4.999));
        assertEquals(TradeFairnessPolicy.Classification.MARKET_FAIR, TradeFairnessPolicy.classify(5.0));
    }

    @Test
    void classifiesAboveFivePercentOutsideBand() {
        assertEquals(TradeFairnessPolicy.Classification.OUTSIDE_FAIRNESS_BAND, TradeFairnessPolicy.classify(5.001));
        assertEquals(TradeFairnessPolicy.Classification.OUTSIDE_FAIRNESS_BAND, TradeFairnessPolicy.classify(25.0));
    }

    @Test
    void unavailableMeasurementRemainsUnavailable() {
        assertEquals(TradeFairnessPolicy.Classification.UNAVAILABLE, TradeFairnessPolicy.classify(null));
    }

    @Test
    void rejectsInvalidMeasurements() {
        assertThrows(IllegalArgumentException.class, () -> TradeFairnessPolicy.classify(-0.1));
        assertThrows(IllegalArgumentException.class, () -> TradeFairnessPolicy.classify(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> TradeFairnessPolicy.classify(Double.POSITIVE_INFINITY));
    }
}
