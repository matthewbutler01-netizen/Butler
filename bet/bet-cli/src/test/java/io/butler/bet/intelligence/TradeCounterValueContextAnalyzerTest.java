package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeCounterValueContextAnalyzerTest {
    private static final LocalDate MINIMUM_AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void comparableTradeReceivesGovernedCounterTarget() {
        var report = TradeCounterValueContextAnalyzer.compose(
            trade(side(105.0, false), side(95.0, false)));

        assertTrue(report.available());
        assertNull(report.insufficiencyReason());
        assertEquals("l1", report.leagueId());
        assertEquals("source", report.source());
        assertEquals(MINIMUM_AS_OF, report.minimumAsOfDate());
        assertEquals(TradeCounterValueTargetAnalyzer.POLICY_ID, report.targetPolicyId());
        assertEquals(TradeFairnessPolicy.Classification.OUTSIDE_FAIRNESS_BAND,
            report.target().currentFairness());
        assertEquals(2, report.target().options().size());
    }

    @Test
    void incompleteTradeFailsClosedWithoutPartialCounterTarget() {
        var report = TradeCounterValueContextAnalyzer.compose(
            trade(missingSide(), side(95.0, false)));

        assertFalse(report.available());
        assertEquals("Trade counter value target requires complete market-value coverage.",
            report.insufficiencyReason());
        assertNull(report.target());
    }

    @Test
    void staleTradeFailsClosedWithoutCounterTarget() {
        var report = TradeCounterValueContextAnalyzer.compose(
            trade(side(105.0, true), side(95.0, false)));

        assertFalse(report.available());
        assertEquals("Trade counter value target requires fresh market-value evidence.",
            report.insufficiencyReason());
        assertNull(report.target());
    }

    @Test
    void comparableMarketFairTradeIsAvailableWithoutAdjustmentOptions() {
        var report = TradeCounterValueContextAnalyzer.compose(
            trade(side(102.0, false), side(100.0, false)));

        assertTrue(report.available());
        assertEquals(TradeFairnessPolicy.Classification.MARKET_FAIR,
            report.target().currentFairness());
        assertTrue(report.target().options().isEmpty());
    }

    @Test
    void contextLocksItsOwnAndTargetPolicyProvenance() {
        var report = TradeCounterValueContextAnalyzer.compose(
            trade(side(105.0, false), side(95.0, false)));

        assertEquals("trade-counter-value-context-v1-comparable-market-evidence", report.policyId());
        assertEquals("trade-counter-value-target-v1-market-fairness-boundary", report.targetPolicyId());
    }

    private static TradeAssetAnalyzer.TradeReport trade(
        TradeAssetAnalyzer.TradeSide sideA,
        TradeAssetAnalyzer.TradeSide sideB) {
        return new TradeAssetAnalyzer.TradeReport(
            "l1", "source", MINIMUM_AS_OF, sideA, sideB);
    }

    private static TradeAssetAnalyzer.TradeSide side(double value, boolean stale) {
        var player = new TradeAssetAnalyzer.TradePlayer(
            "p-" + value,
            "Player " + value,
            "WR",
            "NFL",
            "t1",
            "Team One",
            value,
            stale ? MINIMUM_AS_OF.minusDays(1) : MINIMUM_AS_OF,
            stale);
        return new TradeAssetAnalyzer.TradeSide(
            List.of(player), List.of(), value, 1, 0, 0, 0);
    }

    private static TradeAssetAnalyzer.TradeSide missingSide() {
        var player = new TradeAssetAnalyzer.TradePlayer(
            "missing",
            "Missing Player",
            "WR",
            "NFL",
            "t1",
            "Team One",
            null,
            null,
            false);
        return new TradeAssetAnalyzer.TradeSide(
            List.of(player), List.of(), 0.0, 0, 1, 0, 0);
    }
}
