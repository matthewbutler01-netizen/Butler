package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeCounterSingleAssetCandidateInventoryGateTest {
    private static final LocalDate MINIMUM_AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void incompleteCounterContextDoesNotRequireInventory() {
        var trade = trade(missingSide(), side("B", 95.0));
        var context = TradeCounterValueContextAnalyzer.compose(trade);

        assertFalse(context.available());
        assertFalse(TradeCounterSingleAssetCandidateAnalyzer.requiresInventory(context));
    }

    @Test
    void alreadyFairCounterContextDoesNotRequireInventory() {
        var trade = trade(side("A", 102.0), side("B", 100.0));
        var context = TradeCounterValueContextAnalyzer.compose(trade);

        assertTrue(context.available());
        assertTrue(context.target().options().isEmpty());
        assertFalse(TradeCounterSingleAssetCandidateAnalyzer.requiresInventory(context));
    }

    @Test
    void outsideBandCounterContextRequiresInventory() {
        var trade = trade(side("A", 105.0), side("B", 95.0));
        var context = TradeCounterValueContextAnalyzer.compose(trade);

        assertTrue(context.available());
        assertFalse(context.target().options().isEmpty());
        assertTrue(TradeCounterSingleAssetCandidateAnalyzer.requiresInventory(context));
    }

    private static TradeAssetAnalyzer.TradeReport trade(
        TradeAssetAnalyzer.TradeSide sideA,
        TradeAssetAnalyzer.TradeSide sideB) {
        return new TradeAssetAnalyzer.TradeReport(
            "l1", "source", MINIMUM_AS_OF, sideA, sideB);
    }

    private static TradeAssetAnalyzer.TradeSide side(String teamId, double value) {
        var player = new TradeAssetAnalyzer.TradePlayer(
            "p-" + teamId,
            "Player " + teamId,
            "WR",
            "NFL",
            teamId,
            "Team " + teamId,
            value,
            MINIMUM_AS_OF,
            false);
        return new TradeAssetAnalyzer.TradeSide(
            List.of(player), List.of(), value, 1, 0, 0, 0);
    }

    private static TradeAssetAnalyzer.TradeSide missingSide() {
        var player = new TradeAssetAnalyzer.TradePlayer(
            "missing", "Missing", "WR", "NFL", "A", "Team A",
            null, null, false);
        return new TradeAssetAnalyzer.TradeSide(
            List.of(player), List.of(), 0.0, 0, 1, 0, 0);
    }
}
