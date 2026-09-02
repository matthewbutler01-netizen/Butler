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
import static org.junit.jupiter.api.Assertions.assertThrows;

class LeagueProductionContextAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void aggregatesRawProductionByTeamAndPositionWithoutScoring() throws Exception {
        Database database = seededLeague(2025);
        PlayerSeasonProductionRepository production = new PlayerSeasonProductionRepository(database);
        production.save(PlayerSeasonProduction.create("p1", 2025, 17, 4000, 30, 10,
            250, 3, 0, 0, 0, 2, "nflverse", LocalDate.of(2026, 1, 10)));
        production.save(PlayerSeasonProduction.create("p2", 2025, 15, 0, 0, 0,
            900, 8, 45, 400, 3, 1, "nflverse", LocalDate.of(2026, 1, 11)));

        var report = new LeagueProductionContextAnalyzer(database).analyze("l1");
        var team = report.teams().getFirst();
        var qb = team.positions().get("QB");
        var rb = team.positions().get("RB");

        assertEquals(2025, report.season());
        assertEquals(3, report.totalPlayers());
        assertEquals(2, report.coveredPlayers());
        assertEquals(2, team.coveredPlayers());
        assertEquals(1, team.missingPlayers().size());
        assertEquals("Player Three", team.missingPlayers().getFirst().playerName());
        assertEquals(4000, qb.passingYards());
        assertEquals(30, qb.passingTouchdowns());
        assertEquals(250, qb.rushingYards());
        assertEquals(15, rb.playerGames());
        assertEquals(900, rb.rushingYards());
        assertEquals(45, rb.receptions());
        assertEquals(400, rb.receivingYards());
        assertEquals(LocalDate.of(2026, 1, 10), team.earliestAsOf());
        assertEquals(LocalDate.of(2026, 1, 11), team.latestAsOf());
    }

    @Test
    void explicitSeasonOverridesStoredLeagueSeason() throws Exception {
        Database database = seededLeague(2026);
        new PlayerSeasonProductionRepository(database).save(PlayerSeasonProduction.create(
            "p1", 2025, 17, 3500, 25, 8, 200, 2, 0, 0, 0, 1,
            "nflverse", LocalDate.of(2026, 1, 10)));

        var report = new LeagueProductionContextAnalyzer(database).analyze("l1", 2025);

        assertEquals(2025, report.season());
        assertEquals(1, report.coveredPlayers());
    }

    @Test
    void storedSeasonIsRequiredWhenNoExplicitSeasonIsSupplied() throws Exception {
        Database database = seededLeague(null);
        assertThrows(IllegalStateException.class,
            () -> new LeagueProductionContextAnalyzer(database).analyze("l1"));
    }

    private Database seededLeague(Integer season) throws Exception {
        Database database = new Database(tempDir.resolve("test-" + (season == null ? "none" : season) + ".db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", null, "League", season));
        new TeamRepository(database).save(new Team("t1", null, "l1", "Alpha"));
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        players.save(new Player("p1", "101", "Player One", "QB", "CHI"));
        players.save(new Player("p2", "102", "Player Two", "RB", "DET"));
        players.save(new Player("p3", "103", "Player Three", "RB", "MIN"));
        rosters.save(new Roster("r1", null, "t1", "p1", "STARTER"));
        rosters.save(new Roster("r2", null, "t1", "p2", "STARTER"));
        rosters.save(new Roster("r3", null, "t1", "p3", "BENCH"));
        return database;
    }
}
