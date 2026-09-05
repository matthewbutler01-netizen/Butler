package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerWeekProductionRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerWeekProduction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaguePlayerWeekScoringAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void scoresLatestPersistedWeekSnapshotUnderExactLeagueRules() throws Exception {
        Database database = leagueDatabase("latest.db");
        new LeagueScoringSettingsRepository(database).replace("league-1", Map.of(
            "pass_yd", 0.04, "pass_td", 4.0, "pass_int", -2.0,
            "rush_yd", 0.1, "rush_td", 6.0, "rec", 1.0,
            "rec_yd", 0.1, "rec_td", 6.0, "fum_lost", -2.0));
        var production = new PlayerWeekProductionRepository(database);
        production.save(new PlayerWeekProduction(
            "old", "p1", 2026, 7, 0, 0, 0, 10, 0, 1, 5, 0, 0,
            "nflverse", LocalDate.of(2026, 10, 19)));
        production.save(new PlayerWeekProduction(
            "new", "p1", 2026, 7, 250, 2, 1, 30, 1, 5, 50, 1, 1,
            "nflverse", LocalDate.of(2026, 10, 20)));

        var report = new LeaguePlayerWeekScoringAnalyzer(database)
            .analyze("league-1", "p1", 2026, 7, "nflverse");

        assertEquals("new", report.productionId());
        assertEquals(LocalDate.of(2026, 10, 20), report.productionAsOf());
        assertEquals(7, report.week());
        assertEquals(7, report.score().week());
        assertEquals(0, report.score().totalPoints().compareTo(new BigDecimal("39")));
        assertEquals(CoveredProductionScoringPolicy.POLICY_ID, report.scoringPolicyId());
    }

    @Test
    void blocksWeeklyScoringWhenLeagueCoverageIsIncomplete() throws Exception {
        Database database = leagueDatabase("unsupported.db");
        new LeagueScoringSettingsRepository(database).replace("league-1", Map.of(
            "rec", 1.0,
            "bonus_rec_te", 0.5));
        new PlayerWeekProductionRepository(database).save(new PlayerWeekProduction(
            "week", "p1", 2026, 7, 0, 0, 0, 0, 0, 5, 50, 0, 0,
            "nflverse", LocalDate.of(2026, 10, 20)));

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            new LeaguePlayerWeekScoringAnalyzer(database)
                .analyze("league-1", "p1", 2026, 7, "nflverse"));

        assertTrue(error.getMessage().contains("Exact league scoring unavailable"));
    }

    @Test
    void failsExplicitlyWhenRequestedPlayerWeekEvidenceIsMissing() throws Exception {
        Database database = leagueDatabase("missing.db");
        new LeagueScoringSettingsRepository(database).replace("league-1", Map.of("rec", 1.0));

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            new LeaguePlayerWeekScoringAnalyzer(database)
                .analyze("league-1", "p1", 2026, 8, "nflverse"));

        assertTrue(error.getMessage().contains("week=8"));
        assertTrue(error.getMessage().contains("source=nflverse"));
    }

    private Database leagueDatabase(String file) throws Exception {
        Database database = new Database(tempDir.resolve(file));
        database.initialize();
        new LeagueRepository(database).save(new League("league-1", "sleeper-1", "Test League", 2026));
        new PlayerRepository(database).save(new Player("p1", "1001", "Player One", "RB", "CHI"));
        return database;
    }
}
