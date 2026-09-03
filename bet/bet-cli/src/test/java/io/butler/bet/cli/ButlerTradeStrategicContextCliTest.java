package io.butler.bet.cli;

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
