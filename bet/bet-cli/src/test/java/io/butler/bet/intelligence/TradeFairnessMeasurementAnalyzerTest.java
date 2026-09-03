package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeFairnessMeasurementAnalyzerTest {
    @Test
    void measuresSymmetricMarketValueGapWithoutDeclaringFairness() {
        var sideAValue = new TradeValueAnalyzer.TradeSide(List.of(player("a", 105.0)), 105.0, 1, 0);
        var sideBValue = new TradeValueAnalyzer.TradeSide(List.of(player("b", 95.0)), 95.0, 1, 0);
        var tradeValue = new TradeValueAnalyzer.TradeReport("l1", "source", sideAValue, sideBValue);
        var trade = packageFor(tradeValue);

        var result = new TradeFairnessMeasurementAnalyzer().analyze(trade);

        assertTrue(result.available());
        assertEquals(10.0, result.absoluteGap());
        assertEquals(10.0, result.symmetricGapPercent());
        assertEquals(10.0, result.signedValueDifference());
        assertEquals(TradeFairnessMeasurementPolicy.POLICY_ID, result.policyId());
        assertTrue(result.interpretationBoundary().contains("no fairness tolerance"));
    }

    @Test
    void incompleteMarketValuesMakeFairnessMeasurementUnavailableEvenWithSupportingEvidence() {
        var sideAValue = new TradeValueAnalyzer.TradeSide(List.of(player("a", 100.0)), 100.0, 1, 0);
        var sideBValue = new TradeValueAnalyzer.TradeSide(List.of(player("b", null)), 0.0, 0, 1);
        var tradeValue = new TradeValueAnalyzer.TradeReport("l1", "source", sideAValue, sideBValue);
        var trade = packageFor(tradeValue);

        var result = new TradeFairnessMeasurementAnalyzer().analyze(trade);

        assertFalse(result.available());
        assertNull(result.absoluteGap());
        assertNull(result.symmetricGapPercent());
        assertNull(result.signedValueDifference());
    }

    @Test
    void midpointFormulaIsSymmetricAndHandlesBothZero() {
        assertEquals(10.0, TradeFairnessMeasurementPolicy.symmetricGapPercent(105.0, 95.0));
        assertEquals(10.0, TradeFairnessMeasurementPolicy.symmetricGapPercent(95.0, 105.0));
        assertEquals(0.0, TradeFairnessMeasurementPolicy.symmetricGapPercent(0.0, 0.0));
    }

    private static TradeSupportingEvidenceAnalyzer.TradeEvidencePackage packageFor(
        TradeValueAnalyzer.TradeReport tradeValue) {
        var sideA = new TradeSupportingEvidenceAnalyzer.TradeEvidenceSide(
            tradeValue.sideA(), tradeValue.sideA().players().stream()
                .map(player -> new TradeSupportingEvidenceAnalyzer.TradePlayerEvidence(player, List.of()))
                .toList());
        var sideB = new TradeSupportingEvidenceAnalyzer.TradeEvidenceSide(
            tradeValue.sideB(), tradeValue.sideB().players().stream()
                .map(player -> new TradeSupportingEvidenceAnalyzer.TradePlayerEvidence(player, List.of()))
                .toList());
        Double symmetricGapPercent = tradeValue.complete()
            ? TradeFairnessMeasurementPolicy.symmetricGapPercent(sideA.value().totalValue(), sideB.value().totalValue())
            : null;
        return new TradeSupportingEvidenceAnalyzer.TradeEvidencePackage(
            tradeValue,
            2026,
            LocalDate.of(2026, 9, 1),
            "support",
            "outlook",
            "profiles",
            "production",
            TradeFairnessMeasurementPolicy.POLICY_ID,
            TradeFairnessPolicy.POLICY_ID,
            symmetricGapPercent,
            TradeFairnessPolicy.classify(symmetricGapPercent),
            sideA,
            sideB);
    }

    private static TradeValueAnalyzer.TradePlayer player(String id, Double value) {
        return new TradeValueAnalyzer.TradePlayer(id, id, "WR", "CHI", "t", "Team", value, LocalDate.of(2026, 9, 1));
    }
}
