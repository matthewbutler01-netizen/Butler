package io.butler.bet.cli;

import io.butler.bet.intelligence.LeaguePositionalPressureAnalyzer;
import io.butler.bet.intelligence.LeaguePositionalPressurePolicy;
import io.butler.bet.intelligence.TradeAssetPositionalContextAnalyzer;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeStrategicContextCliTest {
    @Test
    void parsesMixedPlayerAndPickPackages() {
        var options = ButlerTradeStrategicContextCli.parse(new String[]{
            "trade", "strategic-context", "l1", "2026", "player:p1,pick:d1", "p2"});
        assertEquals("l1", options.leagueId());
        assertEquals(2026, options.season());
        assertEquals(List.of("p1"), options.sideA().playerIds());
        assertEquals(List.of("d1"), options.sideA().draftPickIds());
        assertEquals(List.of("p2"), options.sideB().playerIds());
        assertNull(options.source());
        assertNull(options.minimumAsOf());
    }

    @Test
    void parsesSourceAndFreshnessBoundary() {
        var options = ButlerTradeStrategicContextCli.parse(new String[]{
            "trade", "strategic-context", "l1", "2026", "p1", "pick:d2",
            "dynastyprocess", "--minimum-as-of", "2026-09-01"});
        assertEquals("dynastyprocess", options.source());
        assertEquals(LocalDate.of(2026, 9, 1), options.minimumAsOf());
    }

    @Test
    void formatsUnavailablePositionalContextReason() {
        var availability = new TradeAssetPositionalContextAnalyzer.PositionAvailability(
            "QB", 1, false, "Complete current value coverage is required for every rostered QB.");

        assertEquals(
            "  QB-context available=false direct-starters=1 reason=Complete current value coverage is required for every rostered QB.",
            ButlerTradeStrategicContextCli.formatPositionAvailability(availability));
    }

    @Test
    void formatsPositionalPressureContract() {
        var pressure = new LeaguePositionalPressureAnalyzer.TeamPositionPressure(
            "t1", "Team 1", 125.5, 240.25, 4, 3, 1, 1,
            LeaguePositionalPressurePolicy.Tier.POSITION_PRESSURE);

        assertEquals(
            "  QB-pressure=POSITION_PRESSURE starter-value=125.50 total-value=240.25 coverage=3/4 stale=1 missing=1",
            ButlerTradeStrategicContextCli.formatPositionPressure("QB", pressure));
    }

    @Test
    void rejectsMalformedInputs() {
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeStrategicContextCli.parse(new String[]{
            "trade", "strategic-context", "l1", "bad", "p1", "p2"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeStrategicContextCli.parse(new String[]{
            "trade", "strategic-context", "l1", "2026", "pick:", "p2"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeStrategicContextCli.parse(new String[]{
            "trade", "strategic-context", "l1", "2026", "p1", "p2", "--minimum-as-of"}));
    }

    @Test
    void recognizesStrategicContextCommand() {
        assertTrue(ButlerTradeStrategicContextCli.isCommand(new String[]{
            "trade", "strategic-context", "l1", "2026", "p1", "p2"}));
    }
}
