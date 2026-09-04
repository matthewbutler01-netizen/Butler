package io.butler.bet.cli;

import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeCounterValueContextAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterValueCliTest {
    private static final LocalDate MINIMUM_AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void parsesMixedTradePackagesSourceAndFreshnessBoundary() {
        var options = ButlerTradeCounterValueCli.parse(new String[]{
            "trade", "counter-value", "l1",
            "player:p1,pick:k1", "p2,pick:k2",
            "dynastyprocess", "--minimum-as-of", "2026-09-01"});

        assertEquals("l1", options.leagueId());
        assertEquals(List.of("p1"), options.sideA().playerIds());
        assertEquals(List.of("k1"), options.sideA().draftPickIds());
        assertEquals(List.of("p2"), options.sideB().playerIds());
        assertEquals(List.of("k2"), options.sideB().draftPickIds());
        assertEquals("dynastyprocess", options.source());
        assertEquals(MINIMUM_AS_OF, options.minimumAsOf());
    }

    @Test
    void parsesFreshnessBoundaryWithoutSource() {
        var options = ButlerTradeCounterValueCli.parse(new String[]{
            "trade", "counter-value", "l1", "p1", "p2",
            "--minimum-as-of", "2026-09-01"});

        assertNull(options.source());
        assertEquals(MINIMUM_AS_OF, options.minimumAsOf());
    }

    @Test
    void printsGovernedTargetsWithoutRecommendationOrAction() {
        var trade = trade(side(105.0, false), side(95.0, false));
        var context = TradeCounterValueContextAnalyzer.compose(trade);
        String output = capture(trade, context);

        assertTrue(output.contains("Trade counter-value evidence (asset-neutral market target)"));
        assertTrue(output.contains("Counter-value evidence available: true"));
        assertTrue(output.contains("Current market fairness: OUTSIDE_FAIRNESS_BAND"));
        assertTrue(output.contains("ADD_TO_LOWER_VALUE_PACKAGE SIDE_B"));
        assertTrue(output.contains("REMOVE_FROM_HIGHER_VALUE_PACKAGE SIDE_A"));
        assertTrue(output.contains("No asset is selected and no COUNTER action is emitted."));
        assertFalse(output.contains("Action:"));
        assertFalse(output.contains("Package recommendation:"));
    }

    @Test
    void printsAlreadyFairTradeWithoutSyntheticAdjustment() {
        var trade = trade(side(102.0, false), side(100.0, false));
        var context = TradeCounterValueContextAnalyzer.compose(trade);
        String output = capture(trade, context);

        assertTrue(output.contains("Current market fairness: MARKET_FAIR"));
        assertTrue(output.contains(
            "Required market-value adjustment: none; the trade is already inside the governed fairness band."));
        assertFalse(output.contains("ADD_TO_LOWER_VALUE_PACKAGE"));
        assertFalse(output.contains("REMOVE_FROM_HIGHER_VALUE_PACKAGE"));
    }

    @Test
    void unavailableEvidencePrintsReasonWithoutPartialTarget() {
        var trade = trade(missingSide(), side(95.0, false));
        var context = TradeCounterValueContextAnalyzer.compose(trade);
        String output = capture(trade, context);

        assertTrue(output.contains("Counter-value evidence available: false"));
        assertTrue(output.contains("Trade counter value target requires complete market-value coverage."));
        assertTrue(output.contains("No partial or stale package total is used to construct a target."));
        assertFalse(output.contains("Current symmetric market-value gap:"));
        assertFalse(output.contains("ADD_TO_LOWER_VALUE_PACKAGE"));
    }

    private static String capture(
        TradeAssetAnalyzer.TradeReport trade,
        TradeCounterValueContextAnalyzer.CounterValueContextReport context) {
        PrintStream original = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerTradeCounterValueCli.print(trade, context);
        } finally {
            System.setOut(original);
        }
        return bytes.toString();
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
            "missing", "Missing Player", "WR", "NFL", "t1", "Team One",
            null, null, false);
        return new TradeAssetAnalyzer.TradeSide(
            List.of(player), List.of(), 0.0, 0, 1, 0, 0);
    }
}
