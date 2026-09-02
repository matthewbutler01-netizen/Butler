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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LeagueValueMoverAnalyzerTest {
    @TempDir
    Path tempDir;

    @Test
    void limitsMoversToLeagueRostersAndIncludesTeamContext() throws Exception {
        Database database = database();
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);

        League targetLeague = new League("league-a", null, "League A");
        League otherLeague = new League("league-b", null, "League B");
        Team alpha = new Team("team-a", null, targetLeague.getId(), "Alpha");
        Team beta = new Team("team-b", null, targetLeague.getId(), "Beta");
        Team outsiderTeam = new Team("team-c", null, otherLeague.getId(), "Gamma");
        leagues.save(targetLeague);
        leagues.save(otherLeague);
        teams.save(alpha);
        teams.save(beta);
        teams.save(outsiderTeam);

        Player riser = Player.create("Riser", "WR", "KC");
        Player faller = Player.create("Faller", "RB", "DET");
        Player outsider = Player.create("Outsider", "QB", "BUF");
        Player single = Player.create("Single", "TE", "DAL");
        players.save(riser);
        players.save(faller);
        players.save(outsider);
        players.save(single);

        rosters.save(new Roster("r1", null, alpha.getId(), riser.getId(), "STARTER"));
        rosters.save(new Roster("r2", null, beta.getId(), faller.getId(), "STARTER"));
        rosters.save(new Roster("r3", null, outsiderTeam.getId(), outsider.getId(), "STARTER"));
        rosters.save(new Roster("r4", null, alpha.getId(), single.getId(), "BENCH"));

        saveHistory(values, riser, 70.0, 90.0);
        saveHistory(values, faller, 100.0, 60.0);
        saveHistory(values, outsider, 10.0, 100.0);
        values.save(PlayerValue.create(single.getId(), 50.0, "market", LocalDate.of(2026, 9, 1)));

        var report = new LeagueValueMoverAnalyzer(database).analyze("  league-a  ", "  market  ");

        assertEquals("league-a", report.leagueId());
        assertEquals("market", report.source());
        assertEquals(LocalDate.of(2026, 8, 1), report.previousDate());
        assertEquals(LocalDate.of(2026, 9, 1), report.latestDate());
        assertEquals(3, report.totalPlayers());
        assertEquals(2, report.comparablePlayers());
        assertEquals(1, report.missingPlayers());
        assertEquals(2, report.movers().size());
        assertEquals(faller.getId(), report.movers().get(0).playerId());
        assertEquals("Beta", report.movers().get(0).teamName());
        assertEquals(-40.0, report.movers().get(0).delta());
        assertEquals(riser.getId(), report.movers().get(1).playerId());
        assertEquals("Alpha", report.movers().get(1).teamName());
        assertEquals(20.0, report.movers().get(1).delta());
    }

    @Test
    void usesOneSourceWideDatePairInsteadOfEachPlayersLatestTwoSnapshots() throws Exception {
        Database database = database();
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);

        League league = new League("league", null, "League");
        Team team = new Team("team", null, league.getId(), "Team");
        Player aligned = Player.create("Aligned", "WR", "KC");
        Player stalePair = Player.create("Stale Pair", "RB", "DET");
        leagues.save(league);
        teams.save(team);
        players.save(aligned);
        players.save(stalePair);
        rosters.save(new Roster("r1", null, team.getId(), aligned.getId(), "STARTER"));
        rosters.save(new Roster("r2", null, team.getId(), stalePair.getId(), "BENCH"));

        values.save(PlayerValue.create(aligned.getId(), 50.0, "market", LocalDate.of(2026, 8, 15)));
        values.save(PlayerValue.create(aligned.getId(), 70.0, "market", LocalDate.of(2026, 9, 1)));
        values.save(PlayerValue.create(stalePair.getId(), 40.0, "market", LocalDate.of(2026, 8, 1)));
        values.save(PlayerValue.create(stalePair.getId(), 45.0, "market", LocalDate.of(2026, 8, 15)));

        var report = new LeagueValueMoverAnalyzer(database).analyze("league", "market");

        assertEquals(LocalDate.of(2026, 8, 15), report.previousDate());
        assertEquals(LocalDate.of(2026, 9, 1), report.latestDate());
        assertEquals(2, report.totalPlayers());
        assertEquals(1, report.comparablePlayers());
        assertEquals(1, report.missingPlayers());
        assertEquals(50.0, report.coveragePercent());
        assertEquals(1, report.movers().size());
        assertEquals(aligned.getId(), report.movers().get(0).playerId());
        assertEquals(20.0, report.movers().get(0).delta());
    }

    @Test
    void reportsNoWindowWhenSourceHasFewerThanTwoSnapshotDates() throws Exception {
        Database database = database();
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);

        League league = new League("league", null, "League");
        Team team = new Team("team", null, league.getId(), "Team");
        Player player = Player.create("Player", "QB", "BUF");
        leagues.save(league);
        teams.save(team);
        players.save(player);
        rosters.save(new Roster("r1", null, team.getId(), player.getId(), "STARTER"));
        values.save(PlayerValue.create(player.getId(), 80.0, "market", LocalDate.of(2026, 9, 1)));

        var report = new LeagueValueMoverAnalyzer(database).analyze("league", "market");

        assertNull(report.previousDate());
        assertNull(report.latestDate());
        assertEquals(1, report.totalPlayers());
        assertEquals(0, report.comparablePlayers());
        assertEquals(1, report.missingPlayers());
        assertEquals(0, report.movers().size());
    }

    @Test
    void rejectsBlankLeagueOrSource() throws Exception {
        Database database = database();
        LeagueValueMoverAnalyzer analyzer = new LeagueValueMoverAnalyzer(database);
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze("   ", "market"));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze("league", "   "));
    }

    private static void saveHistory(PlayerValueRepository values, Player player,
                                    double previous, double latest) throws Exception {
        values.save(PlayerValue.create(player.getId(), previous, "market", LocalDate.of(2026, 8, 1)));
        values.save(PlayerValue.create(player.getId(), latest, "market", LocalDate.of(2026, 9, 1)));
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("league-movers.db"));
        database.initialize();
        return database;
    }
}
