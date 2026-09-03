package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerProductionHistoryCliTest {
    @Test
    void parsesPreviewAndRefreshRanges() {
        var preview = ButlerProductionHistoryCli.parse(new String[]{
            "nflverse", "production-history-preview", "2022", "2025"});
        assertEquals(2022, preview.startSeason());
        assertEquals(2025, preview.endSeason());
        assertFalse(preview.persist());

        var refresh = ButlerProductionHistoryCli.parse(new String[]{
            "nflverse", "production-history-refresh", "2022", "2025"});
        assertTrue(refresh.persist());
    }

    @Test
    void rejectsBadRangesAndUnexpectedArguments() {
        assertThrows(IllegalArgumentException.class, () -> ButlerProductionHistoryCli.parse(new String[]{
            "nflverse", "production-history-preview", "2025", "2024"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerProductionHistoryCli.parse(new String[]{
            "nflverse", "production-history-preview", "1998", "2024"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerProductionHistoryCli.parse(new String[]{
            "nflverse", "production-history-preview", "2024"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerProductionHistoryCli.parse(new String[]{
            "nflverse", "production-history-preview", "2024", "2025", "extra"}));
    }

    @Test
    void recognizesOnlyHistoryCommands() {
        assertTrue(ButlerProductionHistoryCli.isCommand(new String[]{
            "nflverse", "production-history-preview", "2022", "2025"}));
        assertTrue(ButlerProductionHistoryCli.isCommand(new String[]{
            "nflverse", "production-history-refresh", "2022", "2025"}));
        assertFalse(ButlerProductionHistoryCli.isCommand(new String[]{
            "nflverse", "production-preview", "2025"}));
    }
}
