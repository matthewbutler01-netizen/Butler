package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
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

class LeagueProductionCoverageAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void reportsNeutralCoverageByTeamAndPositionAndNamesMissingPlayers() throws Exception {
        Database database = initialized();
        new LeagueRepository(database).save(new League("l1", "ext-l1", "League"));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "Alpha"));
        new PlayerRepository(database).save(new Player("p1", "1001", "Covered Runner", "RB", "KC"));
        new PlayerRepository(database).save(new Player("p2", "1002", "Missing Receiver", "WR", "BUF"));
        new RosterRepository(database).save(new Roster("r1", null, "t1", "p1", "STARTER"));
        new RosterRepository(database).save(new Roster("r2", null, "t1", "p2", "BENCH"));
        new PlayerSeasonProductionRepository(database).save(PlayerSeasonProduction.create(
            "p1", 2025, 17, 0, 0, 0, 1000, 8, 20, 200, 1, 1, "nflverse", LocalDate.of(2026, 1, 10)));

        var report = new LeagueProductionCoverageAnalyzer(database).analyze("l1", 2025);

        assertEquals(1, report.coveredPlayers());
        assertEquals(2, report.totalPlayers());
        assertEquals(50.0, report.coveragePercent());
        assertFalse(report.complete());
        var team = report.teams().getFirst();
        assertEquals(17, team.gamesRecorded());
        assertEquals(1, team.positions().get("RB").coveredPlayers());
        assertEquals(0, team.positions().get("WR").coveredPlayers());
        assertEquals("Missing Receiver", team.missing().getFirst().playerName());
        assertEquals("BENCH", team.missing().getFirst().rosterSlot());
    }

    @Test
    void sourceParameterKeepsProviderCoverageSeparate() throws Exception {
        Database database = initialized();
        new LeagueRepository(database).save(new League("l1", null, "League"));
        new TeamRepository(database).save(new Team("t1", null, "l1", "Alpha"));
        new PlayerRepository(database).save(new Player("p1", "1001", "Runner", "RB", null));
        new RosterRepository(database).save(new Roster("r1", null, "t1", "p1", "STARTER"));
        new PlayerSeasonProductionRepository(database).save(PlayerSeasonProduction.create(
            "p1", 2025, 17, 0, 0, 0, 1000, 8, 0, 0, 0, 0, "other-source", LocalDate.of(2026, 1, 10)));

        var nflverse = new LeagueProductionCoverageAnalyzer(database).analyze("l1", 2025);
        var other = new LeagueProductionCoverageAnalyzer(database).analyze("l1", 2025, "other-source");

        assertEquals(0, nflverse.coveredPlayers());
        assertEquals(1, other.coveredPlayers());
        assertTrue(other.complete());
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        return database;
    }
}
