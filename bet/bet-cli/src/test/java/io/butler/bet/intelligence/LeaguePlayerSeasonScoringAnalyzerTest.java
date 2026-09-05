package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerSeasonProduction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaguePlayerSeasonScoringAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void scoresLatestPersistedProductionSnapshotWithLeagueRules() throws Exception {
        Database database = database("score.db");
        new LeagueScoringSettingsRepository(database).replace("league-1", Map.of(
            "pass_yd", 0.04,
            "pass_td", 4.0,
            "pass_int", -2.0,
            "rush_yd", 0.1,
            "rush_td", 6.0,
            "rec", 1.0,
            "rec_yd", 0.1,
            "rec_td", 6.0,
            "fum_lost", -2.0));
        var production = new PlayerSeasonProductionRepository(database);
        production.save(row("prod-old", 200, 1, LocalDate.of(2026, 12, 1)));
        production.save(row("prod-new", 300, 2, LocalDate.of(2027, 1, 15)));

        var report = new LeaguePlayerSeasonScoringAnalyzer(database)
            .analyze("league-1", "player-1", 2026, "nflverse");

        assertEquals("prod-new", report.productionId());
        assertEquals(LocalDate.of(2027, 1, 15), report.productionAsOf());
        assertEquals(0, report.score().totalPoints().compareTo(new BigDecimal("18.0")));
        assertEquals(LeagueScoringCoverageAnalyzer.POLICY_ID, report.coveragePolicyId());
        assertEquals(CoveredProductionScoringPolicy.POLICY_ID, report.scoringPolicyId());
    }

    @Test
    void blocksScoringWhenLeagueCoverageIsIncomplete() throws Exception {
        Database database = database("incomplete.db");
        new LeagueScoringSettingsRepository(database).replace("league-1", Map.of(
            "pass_td", 4.0,
            "bonus_pass_yd_300", 3.0));
        new PlayerSeasonProductionRepository(database)
            .save(row("prod-1", 300, 2, LocalDate.of(2027, 1, 15)));

        var error = assertThrows(IllegalStateException.class,
            () -> new LeaguePlayerSeasonScoringAnalyzer(database)
                .analyze("league-1", "player-1", 2026, "nflverse"));

        assertTrue(error.getMessage().contains("Exact league scoring unavailable"));
    }

    @Test
    void reportsMissingProductionExplicitly() throws Exception {
        Database database = database("missing.db");
        new LeagueScoringSettingsRepository(database).replace("league-1", Map.of("rec", 1.0));

        var error = assertThrows(IllegalStateException.class,
            () -> new LeaguePlayerSeasonScoringAnalyzer(database)
                .analyze("league-1", "player-1", 2026, "nflverse"));

        assertTrue(error.getMessage().contains("No persisted production"));
        assertTrue(error.getMessage().contains("source=nflverse"));
    }

    private Database database(String file) throws Exception {
        Database database = new Database(tempDir.resolve(file));
        database.initialize();
        new LeagueRepository(database).save(new League("league-1", "sleeper-1", "Test League", 2026));
        new PlayerRepository(database).save(new Player("player-1", "sleeper-player-1", "Test Player", "QB", "CHI"));
        return database;
    }

    private static PlayerSeasonProduction row(String id, int passingYards, int passingTouchdowns, LocalDate asOf) {
        return new PlayerSeasonProduction(
            id, "player-1", 2026, 17,
            passingYards, passingTouchdowns, 1,
            0, 0, 0, 0, 0,
            0, "nflverse", asOf);
    }
}
