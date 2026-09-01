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
    void persistsAndQueriesCompleteFantasyDataGraph() throws Exception {
        Database database = initializedDatabase("graph.db");
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);

        League league = new League("league-1", "sleeper-league-1", "Butler Dynasty");
        Team team = new Team("team-1", "sleeper-roster-1", league.getId(), "The Butler");
        Player player = new Player("player-1", "sleeper-player-1", "Test Quarterback", "QB", "CHI");
        Roster roster = new Roster("roster-1", "external-roster-row-1", team.getId(), player.getId(), "STARTER");

        leagues.save(league);
        teams.save(team);
        players.save(player);
        rosters.save(roster);

        assertEquals(league, leagues.findById(league.getId()).orElseThrow());
        assertEquals(league, leagues.findByExternalId("sleeper-league-1").orElseThrow());
        assertEquals(team, teams.findById(team.getId()).orElseThrow());
        assertEquals(team, teams.findByExternalId(league.getId(), "sleeper-roster-1").orElseThrow());
        assertEquals(player, players.findById(player.getId()).orElseThrow());
        assertEquals(player, players.findByExternalId("sleeper-player-1").orElseThrow());
        assertEquals(roster, rosters.findById(roster.getId()).orElseThrow());
        assertEquals(roster, rosters.findByTeamAndPlayer(team.getId(), player.getId()).orElseThrow());

        assertEquals(1, leagues.findAll().size());
        assertEquals(1, teams.findByLeagueId(league.getId()).size());
        assertEquals(1, players.findAll().size());
        assertEquals(1, rosters.findByTeamId(team.getId()).size());
        assertEquals(1, rosters.findByPlayerId(player.getId()).size());
    }

    @Test
    void repositoryDeleteOperationsWorkAndReportWhetherRowsExisted() throws Exception {
        Database database = initializedDatabase("delete.db");
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);

        League league = League.create("Delete League");
        Team team = Team.create(league.getId(), "Delete Team");
        Player firstPlayer = Player.create("First Player", "WR", "CHI");
        Player secondPlayer = Player.create("Second Player", "RB", "DET");
        Roster firstRoster = Roster.create(team.getId(), firstPlayer.getId(), "STARTER");
        Roster secondRoster = Roster.create(team.getId(), secondPlayer.getId(), "BENCH");

        leagues.save(league);
        teams.save(team);
        players.save(firstPlayer);
        players.save(secondPlayer);
        rosters.save(firstRoster);
        rosters.save(secondRoster);

        assertTrue(rosters.deleteById(firstRoster.getId()));
        assertFalse(rosters.deleteById(firstRoster.getId()));
        assertTrue(rosters.findById(firstRoster.getId()).isEmpty());

        assertTrue(rosters.deleteByTeamAndPlayer(team.getId(), secondPlayer.getId()));
        assertFalse(rosters.deleteByTeamAndPlayer(team.getId(), secondPlayer.getId()));
        assertTrue(rosters.findByTeamAndPlayer(team.getId(), secondPlayer.getId()).isEmpty());

        assertTrue(players.deleteById(firstPlayer.getId()));
        assertFalse(players.deleteById(firstPlayer.getId()));

        assertTrue(teams.deleteById(team.getId()));
        assertFalse(teams.deleteById(team.getId()));

        assertTrue(leagues.deleteById(league.getId()));
        assertFalse(leagues.deleteById(league.getId()));
    }

    @Test
    void deletingLeagueCascadesToTeamAndRosterButNotPlayer() throws Exception {
        Database database = initializedDatabase("cascade.db");
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

        assertTrue(leagues.deleteById(league.getId()));

        assertTrue(teams.findById(team.getId()).isEmpty());
        assertTrue(rosters.findById(roster.getId()).isEmpty());
        assertTrue(players.findById(player.getId()).isPresent());
    }

    @Test
    void deletingTeamCascadesRosterMembershipButKeepsPlayer() throws Exception {
        Database database = initializedDatabase("team-cascade.db");
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);

        League league = League.create("Team Cascade League");
        Team team = Team.create(league.getId(), "Temporary Team");
        Player player = Player.create("Reusable Player", "TE", "GB");
        Roster roster = Roster.create(team.getId(), player.getId(), "BENCH");

        leagues.save(league);
        teams.save(team);
        players.save(player);
        rosters.save(roster);

        assertTrue(teams.deleteById(team.getId()));
        assertTrue(rosters.findById(roster.getId()).isEmpty());
        assertTrue(players.findById(player.getId()).isPresent());
        assertTrue(leagues.findById(league.getId()).isPresent());
    }

    @Test
    void deletingPlayerCascadesRosterMembershipButKeepsTeam() throws Exception {
        Database database = initializedDatabase("player-cascade.db");
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);

        League league = League.create("Player Cascade League");
        Team team = Team.create(league.getId(), "Stable Team");
        Player player = Player.create("Departing Player", "RB", "MIN");
        Roster roster = Roster.create(team.getId(), player.getId(), "STARTER");

        leagues.save(league);
        teams.save(team);
        players.save(player);
        rosters.save(roster);

        assertTrue(players.deleteById(player.getId()));
        assertTrue(rosters.findById(roster.getId()).isEmpty());
        assertTrue(teams.findById(team.getId()).isPresent());
    }

    @Test
    void databaseConstraintsRejectInvalidRelationshipsAndDuplicateMembership() throws Exception {
        Database database = initializedDatabase("constraints.db");
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);

        League league = League.create("Constraint League");
        leagues.save(league);

        Team invalidTeam = Team.create("missing-league", "Orphan Team");
        assertThrows(java.sql.SQLException.class, () -> teams.save(invalidTeam));

        Team team = Team.create(league.getId(), "Valid Team");
        Player player = Player.create("Valid Player", "QB", "KC");
        teams.save(team);
        players.save(player);

        Roster first = Roster.create(team.getId(), player.getId(), "STARTER");
        Roster duplicateMembership = Roster.create(team.getId(), player.getId(), "BENCH");
        rosters.save(first);
        assertThrows(java.sql.SQLException.class, () -> rosters.save(duplicateMembership));

        Roster missingPlayer = Roster.create(team.getId(), "missing-player", "BENCH");
        assertThrows(java.sql.SQLException.class, () -> rosters.save(missingPlayer));
    }

    @Test
    void repositoriesRejectInvalidInputsEarly() throws Exception {
        Database database = initializedDatabase("validation.db");

        assertThrows(NullPointerException.class, () -> new LeagueRepository(null));
        assertThrows(NullPointerException.class, () -> new TeamRepository(null));
        assertThrows(NullPointerException.class, () -> new PlayerRepository(null));
        assertThrows(NullPointerException.class, () -> new RosterRepository(null));

        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);

        assertThrows(IllegalArgumentException.class, () -> leagues.findById(" "));
        assertThrows(IllegalArgumentException.class, () -> teams.findByLeagueId(""));
        assertThrows(IllegalArgumentException.class, () -> players.findByExternalId(" "));
        assertThrows(IllegalArgumentException.class, () -> rosters.findByTeamAndPlayer("team", " "));

        assertThrows(NullPointerException.class, () -> leagues.save(null));
        assertThrows(NullPointerException.class, () -> teams.save(null));
        assertThrows(NullPointerException.class, () -> players.save(null));
        assertThrows(NullPointerException.class, () -> rosters.save(null));
    }

    private Database initializedDatabase(String filename) throws Exception {
        Database database = new Database(tempDir.resolve(filename));
        database.initialize();
        return database;
    }
}
