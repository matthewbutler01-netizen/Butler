package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeMarketEdgePolicyTest {
    @Test
    void namesOnlyTheHigherMarketValueSideOutsideFairnessBand() {
        assertEquals(TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE,
            TradeMarketEdgePolicy.classify(
                TradeFairnessPolicy.Classification.OUTSIDE_FAIRNESS_BAND, 10.0));
        assertEquals(TradeMarketEdgePolicy.Direction.SIDE_B_MARKET_EDGE,
            TradeMarketEdgePolicy.classify(
                TradeFairnessPolicy.Classification.OUTSIDE_FAIRNESS_BAND, -10.0));
    }

    @Test
    void fairTradesRemainFairWithoutDirectionalEdge() {
        assertEquals(TradeMarketEdgePolicy.Direction.MARKET_FAIR,
            TradeMarketEdgePolicy.classify(
                TradeFairnessPolicy.Classification.MARKET_FAIR, 4.0));
        assertEquals(TradeMarketEdgePolicy.Direction.MARKET_FAIR,
            TradeMarketEdgePolicy.classify(
                TradeFairnessPolicy.Classification.MARKET_FAIR, -4.0));
    }

    @Test
    void incompleteTradesRemainUnavailable() {
        assertEquals(TradeMarketEdgePolicy.Direction.UNAVAILABLE,
            TradeMarketEdgePolicy.classify(
                TradeFairnessPolicy.Classification.UNAVAILABLE, null));
        assertThrows(IllegalArgumentException.class, () -> TradeMarketEdgePolicy.classify(
            TradeFairnessPolicy.Classification.UNAVAILABLE, 1.0));
    }

    @Test
    void rejectsInconsistentAvailableInputs() {
        assertThrows(IllegalArgumentException.class, () -> TradeMarketEdgePolicy.classify(
            TradeFairnessPolicy.Classification.OUTSIDE_FAIRNESS_BAND, null));
        assertThrows(IllegalArgumentException.class, () -> TradeMarketEdgePolicy.classify(
            TradeFairnessPolicy.Classification.OUTSIDE_FAIRNESS_BAND, 0.0));
        assertThrows(IllegalArgumentException.class, () -> TradeMarketEdgePolicy.classify(
            null, 1.0));
    }
}
