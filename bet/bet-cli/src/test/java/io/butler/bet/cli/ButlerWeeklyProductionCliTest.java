package io.butler.bet.cli;

import io.butler.bet.intelligence.NflversePlayerWeekProductionImporter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWeeklyProductionCliTest {
    @Test
    void parsesPreviewAndRefreshCommands() {
        var preview = ButlerWeeklyProductionCli.parse(
            new String[]{"nflverse", "weekly-production-preview", "2025"});
        var refresh = ButlerWeeklyProductionCli.parse(
            new String[]{"nflverse", "weekly-production-refresh", "2025"});

        assertEquals(2025, preview.season());
        assertFalse(preview.persist());
        assertTrue(refresh.persist());
        assertEquals(ButlerCommandRouter.Route.WEEKLY_PRODUCTION,
            ButlerCommandRouter.route(new String[]{"nflverse", "weekly-production-refresh", "2025"}));
    }

    @Test
    void rejectsMalformedSeason() {
        assertThrows(IllegalArgumentException.class, () -> ButlerWeeklyProductionCli.parse(
            new String[]{"nflverse", "weekly-production-preview", "bad"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerWeeklyProductionCli.parse(
            new String[]{"nflverse", "weekly-production-preview"}));
    }

    @Test
    void printsRawEvidenceBoundaryAndCounts() {
        var result = new NflversePlayerWeekProductionImporter.ImportResult(
            2025, LocalDate.of(2026, 1, 20), false, 100, 90, 80, 70, 10, 0, List.of());
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerWeeklyProductionCli.print(result);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains("weekly production preview"));
        assertTrue(output.contains("regular-season=80"));
        assertTrue(output.contains("Matched player-weeks: 70"));
        assertTrue(output.contains("no fantasy scoring, lineup optimization, ranking, or recommendation"));
    }
}
