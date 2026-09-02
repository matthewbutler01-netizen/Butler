package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerProfileSnapshotRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerProfileSnapshot;
import io.butler.bet.domain.PlayerSeasonProduction;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueEvidenceOverviewAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void keepsDecisionAndPlayerEvidenceReadinessIndependent() throws Exception {
        Database database = seeded(2025);
        new PlayerProfileSnapshotRepository(database).save(
            PlayerProfileSnapshot.create("p1", 25, 2, "sleeper", LocalDate.of(2026, 9, 1)));
        new PlayerSeasonProductionRepository(database).save(PlayerSeasonProduction.create(
            "p1", 2025, 17, 0, 0, 0, 500, 4, 20, 150, 1, 0, "nflverse", LocalDate.of(2026, 1, 10)));

        var report = new LeagueEvidenceOverviewAnalyzer(database).analyze("l1");

        assertEquals("l1", report.leagueId());
        assertEquals(2025, report.playerSeason());
        assertEquals(LeagueDecisionReadinessAnalyzer.DecisionReadiness.BLOCKED,
            report.decisionReadiness().readiness());
        assertEquals(LeaguePlayerEvidenceReadinessAnalyzer.Readiness.READY,
            report.playerEvidenceReadiness().readiness());
        assertFalse(report.currentValueDecisionsReady());
        assertTrue(report.playerEvidenceReady());
    }

    @Test
    void explicitPlayerSeasonOverridesStoredLeagueSeasonWithoutChangingDecisionDimension() throws Exception {
        Database database = seeded(2026);
        new PlayerProfileSnapshotRepository(database).save(
            PlayerProfileSnapshot.create("p1", 25, 2, "sleeper", LocalDate.of(2026, 9, 1)));
        new PlayerSeasonProductionRepository(database).save(PlayerSeasonProduction.create(
            "p1", 2025, 17, 0, 0, 0, 500, 4, 20, 150, 1, 0, "nflverse", LocalDate.of(2026, 1, 10)));

        var report = new LeagueEvidenceOverviewAnalyzer(database).analyze("l1", 2025);

        assertEquals(2025, report.playerSeason());
        assertEquals(LeagueDecisionReadinessAnalyzer.DecisionReadiness.BLOCKED,
            report.decisionReadiness().readiness());
        assertTrue(report.playerEvidenceReady());
    }

    private Database seeded(Integer season) throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", null, "League", season));
        new TeamRepository(database).save(new Team("t1", null, "l1", "Alpha"));
        new PlayerRepository(database).save(new Player("p1", "101", "Player One", "RB", null));
        new RosterRepository(database).save(new Roster("r1", null, "t1", "p1", "STARTER"));
        return database;
    }
}
