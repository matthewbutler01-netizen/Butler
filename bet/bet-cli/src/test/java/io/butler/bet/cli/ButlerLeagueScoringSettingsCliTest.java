package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.domain.League;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueScoringSettingsCliTest {
    @TempDir Path tempDir;

    @Test
    void printsStoredProviderRulesWithoutCalculatingFantasyTotals() throws Exception {
        Database database = database("scoring-output.db");
        new LeagueRepository(database).save(new League("league-1", "sleeper-1", "Test League", 2026));
        new LeagueScoringSettingsRepository(database).replace("league-1", Map.of(
            "pass_td", 6.0,
            "pass_int", -2.0,
            "rec", 0.5));

        String output = capture(() -> ButlerLeagueScoringSettingsCli.run(database, "league-1"));

        assertTrue(output.contains("League scoring settings"));
        assertTrue(output.contains("League: Test League (league-1)"));
        assertTrue(output.contains("External league ID: sleeper-1"));
        assertTrue(output.contains("pass_int | -2"));
        assertTrue(output.contains("pass_td | 6"));
        assertTrue(output.contains("rec | 0.5"));
        assertTrue(output.contains("does not calculate player fantasy totals or recommendations"));
    }

    @Test
    void emptyStoredMapIsExplicitAndDoesNotInventDefaults() throws Exception {
        Database database = database("scoring-empty.db");
        new LeagueRepository(database).save(new League("league-1", "sleeper-1", "Test League", 2026));

        String output = capture(() -> ButlerLeagueScoringSettingsCli.run(database, "league-1"));

        assertTrue(output.contains("No persisted scoring settings are available"));
        assertTrue(output.contains("Run a Sleeper league sync"));
    }

    @Test
    void parseAndRouterRequireTheExactCommandShape() {
        var options = ButlerLeagueScoringSettingsCli.parse(
            new String[]{"league", "scoring-settings", "league-1"});
        assertEquals("league-1", options.leagueId());
        assertEquals(ButlerCommandRouter.Route.LEAGUE_SCORING_SETTINGS,
            ButlerCommandRouter.route(new String[]{"league", "scoring-settings", "league-1"}));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueScoringSettingsCli.parse(new String[]{"league", "scoring-settings"}));
        assertTrue(error.getMessage().contains("Usage: butler league scoring-settings <league-id>"));
    }

    @Test
    void missingLeagueFailsExplicitly() throws Exception {
        Database database = database("scoring-missing.db");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueScoringSettingsCli.run(database, "missing"));
        assertTrue(error.getMessage().contains("League not found: missing"));
    }

    private Database database(String filename) throws Exception {
        Database database = new Database(tempDir.resolve(filename));
        database.initialize();
        return database;
    }

    private static String capture(ThrowingRunnable runnable) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            runnable.run();
        } finally {
            System.setOut(original);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
