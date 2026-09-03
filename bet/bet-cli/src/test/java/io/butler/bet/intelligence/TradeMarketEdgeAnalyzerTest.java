package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TradeMarketEdgeAnalyzerTest {
    @Test
    void derivesSideAEdgeWithoutUsingSupportingFlags() {
        var trade = packageFor(110.0, 90.0, TradeFairnessPolicy.Classification.OUTSIDE_FAIRNESS_BAND);

        var report = new TradeMarketEdgeAnalyzer().analyze(trade);

        assertEquals(TradeMarketEdgePolicy.POLICY_ID, report.policyId());
        assertEquals(TradeFairnessPolicy.POLICY_ID, report.fairnessPolicyId());
        assertEquals(20.0, report.signedValueDifference());
        assertEquals(TradeMarketEdgePolicy.Direction.SIDE_A_MARKET_EDGE, report.direction());
    }

    @Test
    void fairTradeRemainsNonDirectional() {
        var trade = packageFor(102.0, 100.0, TradeFairnessPolicy.Classification.MARKET_FAIR);

        var report = new TradeMarketEdgeAnalyzer().analyze(trade);

        assertEquals(TradeMarketEdgePolicy.Direction.MARKET_FAIR, report.direction());
    }

    @Test
    void incompleteTradeRemainsUnavailable() {
        var trade = incompletePackage();

        var report = new TradeMarketEdgeAnalyzer().analyze(trade);

        assertEquals(TradeMarketEdgePolicy.Direction.UNAVAILABLE, report.direction());
        assertNull(report.signedValueDifference());
    }

    private static TradeSupportingEvidenceAnalyzer.TradeEvidencePackage packageFor(
        double sideAValue, double sideBValue, TradeFairnessPolicy.Classification fairness) {
        var sideAPlayer = player("a", sideAValue);
        var sideBPlayer = player("b", sideBValue);
        var sideA = new TradeValueAnalyzer.TradeSide(List.of(sideAPlayer), sideAValue, 1, 0);
        var sideB = new TradeValueAnalyzer.TradeSide(List.of(sideBPlayer), sideBValue, 1, 0);
        var report = new TradeValueAnalyzer.TradeReport("l1", "source", sideA, sideB);
        var evidenceA = new TradeSupportingEvidenceAnalyzer.TradeEvidenceSide(
            sideA, List.of(new TradeSupportingEvidenceAnalyzer.TradePlayerEvidence(sideAPlayer, List.of())));
        var evidenceB = new TradeSupportingEvidenceAnalyzer.TradeEvidenceSide(
            sideB, List.of(new TradeSupportingEvidenceAnalyzer.TradePlayerEvidence(sideBPlayer, List.of())));
        return new TradeSupportingEvidenceAnalyzer.TradeEvidencePackage(
            report, 2026, LocalDate.of(2026, 9, 1), "support", "outlook", "profiles", "production",
            TradeFairnessMeasurementPolicy.POLICY_ID, TradeFairnessPolicy.POLICY_ID,
            TradeFairnessMeasurementPolicy.symmetricGapPercent(sideAValue, sideBValue), fairness,
            evidenceA, evidenceB);
    }

    private static TradeSupportingEvidenceAnalyzer.TradeEvidencePackage incompletePackage() {
        var sideAPlayer = player("a", 100.0);
        var sideBPlayer = new TradeValueAnalyzer.TradePlayer(
            "b", "b", "WR", "CHI", "t", "Team", null, null);
        var sideA = new TradeValueAnalyzer.TradeSide(List.of(sideAPlayer), 100.0, 1, 0);
        var sideB = new TradeValueAnalyzer.TradeSide(List.of(sideBPlayer), 0.0, 0, 1);
        var report = new TradeValueAnalyzer.TradeReport("l1", "source", sideA, sideB);
        var evidenceA = new TradeSupportingEvidenceAnalyzer.TradeEvidenceSide(
            sideA, List.of(new TradeSupportingEvidenceAnalyzer.TradePlayerEvidence(sideAPlayer, List.of())));
        var evidenceB = new TradeSupportingEvidenceAnalyzer.TradeEvidenceSide(
            sideB, List.of(new TradeSupportingEvidenceAnalyzer.TradePlayerEvidence(sideBPlayer, List.of())));
        return new TradeSupportingEvidenceAnalyzer.TradeEvidencePackage(
            report, 2026, LocalDate.of(2026, 9, 1), "support", "outlook", "profiles", "production",
            TradeFairnessMeasurementPolicy.POLICY_ID, TradeFairnessPolicy.POLICY_ID,
            null, TradeFairnessPolicy.Classification.UNAVAILABLE, evidenceA, evidenceB);
    }

    private static TradeValueAnalyzer.TradePlayer player(String id, double value) {
        return new TradeValueAnalyzer.TradePlayer(
            id, id, "WR", "CHI", "t", "Team", value, LocalDate.of(2026, 9, 1));
    }
}
