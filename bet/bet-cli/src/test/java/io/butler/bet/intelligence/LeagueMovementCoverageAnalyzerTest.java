package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerValue;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueMovementCoverageAnalyzerTest {
    @TempDir
    Path tempDir;

    @Test
    void identifiesWhichAlignedSnapshotEachRosteredPlayerIsMissing() throws Exception {
        Database database = database("coverage");
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);

        League league = new League("league", null, "League");
        Team alpha = new Team("alpha", null, league.getId(), "Alpha");
        Team beta = new Team("beta", null, league.getId(), "Beta");
        Player complete = Player.create("Complete", "WR", "KC");
        Player noPrevious = Player.create("No Previous", "RB", "DET");
        Player noLatest = Player.create("No Latest", "QB", "BUF");
        leagues.save(league);
        teams.save(alpha);
        teams.save(beta);
        players.save(complete);
        players.save(noPrevious);
        players.save(noLatest);
        rosters.save(new Roster("r1", null, alpha.getId(), complete.getId(), "STARTER"));
        rosters.save(new Roster("r2", null, alpha.getId(), noPrevious.getId(), "BENCH"));
        rosters.save(new Roster("r3", null, beta.getId(), noLatest.getId(), "STARTER"));

        save(values, complete, 10.0, LocalDate.of(2026, 8, 1));
        save(values, complete, 15.0, LocalDate.of(2026, 9, 1));
        save(values, noPrevious, 20.0, LocalDate.of(2026, 9, 1));
        save(values, noLatest, 30.0, LocalDate.of(2026, 8, 1));

        var report = new LeagueMovementCoverageAnalyzer(database).analyze(" league ", " market ");

        assertEquals(LocalDate.of(2026, 8, 1), report.previousDate());
        assertEquals(LocalDate.of(2026, 9, 1), report.latestDate());
        assertEquals(3, report.totalPlayers());
        assertEquals(1, report.comparablePlayers());
        assertEquals(2, report.missingPlayers());
        assertEquals(100.0 / 3.0, report.coveragePercent(), 0.0001);

        var first = report.missingSnapshots().get(0);
        assertEquals("No Previous", first.playerName());
        assertTrue(first.missingPrevious());
        assertFalse(first.missingLatest());
        assertEquals("Alpha", first.teamName());

        var second = report.missingSnapshots().get(1);
        assertEquals("No Latest", second.playerName());
        assertFalse(second.missingPrevious());
        assertTrue(second.missingLatest());
        assertEquals("Beta", second.teamName());
    }

    @Test
    void reportsUnavailableWindowWithoutInventingPerPlayerMissingDates() throws Exception {
        Database database = database("unavailable");
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);

        League league = new League("league", null, "League");
        Team team = new Team("team", null, league.getId(), "Team");
        Player player = Player.create("Player", "TE", "DAL");
        leagues.save(league);
        teams.save(team);
        players.save(player);
        rosters.save(new Roster("r1", null, team.getId(), player.getId(), "STARTER"));
        save(values, player, 50.0, LocalDate.of(2026, 9, 1));

        var report = new LeagueMovementCoverageAnalyzer(database).analyze("league", "market");

        assertNull(report.previousDate());
        assertNull(report.latestDate());
        assertEquals(1, report.totalPlayers());
        assertEquals(0, report.comparablePlayers());
        assertEquals(1, report.missingPlayers());
        assertTrue(report.missingSnapshots().isEmpty());
    }

    private static void save(PlayerValueRepository values, Player player, double value, LocalDate date) throws Exception {
        values.save(PlayerValue.create(player.getId(), value, "market", date));
    }

    private Database database(String name) throws Exception {
        Database database = new Database(tempDir.resolve(name + ".db"));
        database.initialize();
        return database;
    }
}
