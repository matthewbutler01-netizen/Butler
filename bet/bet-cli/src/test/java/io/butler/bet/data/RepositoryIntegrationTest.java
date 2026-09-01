package io.butler.bet.data;

import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsLeagueTeamPlayerAndRoster() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();

        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);

        League league = League.create("Butler Dynasty");
        Team team = Team.create(league.getId(), "The Butler");
        Player player = Player.create("Test Quarterback", "QB", "CHI");
        Roster roster = Roster.create(team.getId(), player.getId(), "STARTER");

        leagues.save(league);
        teams.save(team);
        players.save(player);
        rosters.save(roster);

        assertEquals(league, leagues.findById(league.getId()).orElseThrow());
        assertEquals(team, teams.findById(team.getId()).orElseThrow());
        assertEquals(player, players.findById(player.getId()).orElseThrow());
        assertEquals(roster, rosters.findById(roster.getId()).orElseThrow());

        assertEquals(1, teams.findByLeagueId(league.getId()).size());
        assertEquals(1, rosters.findByTeamId(team.getId()).size());
    }

    @Test
    void deletingLeagueCascadesToTeamAndRosterButNotPlayer() throws Exception {
        Database database = new Database(tempDir.resolve("cascade.db"));
        database.initialize();

        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);

        League league = League.create("Cascade League");
        Team team = Team.create(league.getId(), "Cascade Team");
        Player player = Player.create("Independent Player", "WR", null);
        Roster roster = Roster.create(team.getId(), player.getId(), "BENCH");

        leagues.save(league);
        teams.save(team);
        players.save(player);
        rosters.save(roster);

        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("DELETE FROM leagues WHERE id=?")) {
            statement.setString(1, league.getId());
            statement.executeUpdate();
        }

        assertTrue(teams.findById(team.getId()).isEmpty());
        assertTrue(rosters.findById(roster.getId()).isEmpty());
        assertTrue(players.findById(player.getId()).isPresent());
    }
}
