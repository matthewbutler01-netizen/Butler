package io.butler.bet.cli;

import io.butler.bet.intelligence.TradeStrategicMaterialLossVetoDetector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerTradeRecommendationMaterialLossFormattingTest {
    @Test
    void keepsOrdinaryMaterialLossAtOneDecimalPlace() {
        assertEquals("26.0%", ButlerTradeRecommendationCli.formatLossPercent(0.26));
    }

    @Test
    void marksBarelyMaterialLossAboveThresholdInsteadOfRoundingToThreshold() {
        assertEquals(">25.0%", ButlerTradeRecommendationCli.formatLossPercent(0.2500001));

        var reason = new TradeStrategicMaterialLossVetoDetector.VetoReason(
            TradeStrategicMaterialLossVetoDetector.ReasonCode.LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS,
            null,
            100.0,
            74.99999,
            0.2500001);

        assertEquals(
            "low future capital: future-pick protected value 100.00 -> 75.00 (>25.0% loss; material when loss > 25.0%)",
            ButlerTradeRecommendationCli.formatVetoReason(reason));
    }

    @Test
    void exactThresholdStillFormatsAsTwentyFivePercent() {
        assertEquals("25.0%", ButlerTradeRecommendationCli.formatLossPercent(0.25));
    }
}
