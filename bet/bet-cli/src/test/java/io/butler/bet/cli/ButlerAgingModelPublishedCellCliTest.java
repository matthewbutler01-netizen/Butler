package io.butler.bet.cli;

import io.butler.bet.intelligence.AgingModelSampleAuditAnalyzer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAgingModelPublishedCellCliTest {
    @Test
    void parsesSupportedCoordinate() {
        String[] args = {"aging-model", "published-cell", "wr", "receiving_yards_per_game", "38"};
        assertTrue(ButlerAgingModelPublishedCellCli.isCommand(args));
        var options = ButlerAgingModelPublishedCellCli.parse(args);
        assertEquals("WR", options.position());
        assertEquals(AgingModelSampleAuditAnalyzer.Metric.RECEIVING_YARDS_PER_GAME, options.metric());
        assertEquals(38, options.age());
    }

    @Test
    void rejectsUnsupportedOrMalformedCoordinates() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerAgingModelPublishedCellCli.parse(
                new String[]{"aging-model", "published-cell", "K", "RECEIVING_YARDS_PER_GAME", "38"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerAgingModelPublishedCellCli.parse(
                new String[]{"aging-model", "published-cell", "WR", "FANTASY_POINTS", "38"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerAgingModelPublishedCellCli.parse(
                new String[]{"aging-model", "published-cell", "WR", "RECEIVING_YARDS_PER_GAME", "-1"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerAgingModelPublishedCellCli.parse(
                new String[]{"aging-model", "published-cell", "WR", "RECEIVING_YARDS_PER_GAME"}));
        assertFalse(ButlerAgingModelPublishedCellCli.isCommand(
            new String[]{"aging-model", "published-smoother"}));
    }
}
