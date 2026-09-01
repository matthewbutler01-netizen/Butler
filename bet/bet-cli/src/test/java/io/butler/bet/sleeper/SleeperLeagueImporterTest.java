package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLeagueImporterTest {
    @TempDir Path tempDir;

    @Test
    void repeatedImportPreservesIdsMapsSlotsAndReconcilesChanges() throws Exception {
        Database database = database();
        FakeGateway gateway = new FakeGateway();
        SleeperLeagueImporter importer = new SleeperLeagueImporter(gateway, database);
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);

        importer.importLeague("L1");

        var league = leagues.findByExternalId("L1").orElseThrow();
        var team1 = teams.findByExternalId(league.getId(), "1").orElseThrow();
        var team2 = teams.findByExternalId(league.getId(), "2").orElseThrow();
        var p1 = players.findByExternalId("p1").orElseThrow();
        var p2 = players.findByExternalId("p2").orElseThrow();
        var p3 = players.findByExternalId("p3").orElseThrow();
        var missing = players.findByExternalId("missing").orElseThrow();

        assertEquals("Butler Dynasty", team1.getName());
        assertEquals("Other Owner", team2.getName());
        assertEquals("STARTER", rosters.findByTeamAndPlayer(team1.getId(), p1.getId()).orElseThrow().getSlot());
        assertEquals("RESERVE", rosters.findByTeamAndPlayer(team1.getId(), p2.getId()).orElseThrow().getSlot());
        assertEquals("TAXI", rosters.findByTeamAndPlayer(team1.getId(), p3.getId()).orElseThrow().getSlot());
        assertEquals("UNKNOWN", missing.getPosition());
        assertEquals("Sleeper Player missing", missing.getDisplayName());

        String leagueId = league.getId();
        String team1Id = team1.getId();
        String p1Id = p1.getId();
        String p1RosterId = rosters.findByTeamAndPlayer(team1Id, p1Id).orElseThrow().getId();

        gateway.secondFixture = true;
        importer.importLeague("L1");

        assertEquals(leagueId, leagues.findByExternalId("L1").orElseThrow().getId());
        assertEquals(team1Id, teams.findByExternalId(leagueId, "1").orElseThrow().getId());
        assertEquals(p1Id, players.findByExternalId("p1").orElseThrow().getId());
        assertEquals(p1RosterId, rosters.findByTeamAndPlayer(team1Id, p1Id).orElseThrow().getId());
        assertEquals("BENCH", rosters.findByTeamAndPlayer(team1Id, p1Id).orElseThrow().getSlot());
        assertTrue(rosters.findByTeamAndPlayer(team1Id, p2.getId()).isEmpty());
        assertTrue(teams.findByExternalId(leagueId, "2").isEmpty());
        assertTrue(players.findByExternalId("p2").isPresent());
    }

    @Test
    void ownerlessRosterUsesFallbackName() throws Exception {
        Database database = database();
        SleeperGateway gateway = new FakeGateway() {
            @Override public List<SleeperJsonParser.SleeperUser> fetchUsers(String leagueId) { return List.of(); }
            @Override public List<SleeperJsonParser.SleeperRoster> fetchRosters(String leagueId) {
                return List.of(new SleeperJsonParser.SleeperRoster(7, null, List.of(), List.of(), List.of(), List.of()));
            }
        };
        new SleeperLeagueImporter(gateway, database).importLeague("L1");
        var league = new LeagueRepository(database).findByExternalId("L1").orElseThrow();
        assertEquals("Roster 7", new TeamRepository(database).findByExternalId(league.getId(), "7").orElseThrow().getName());
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("butler-test.db"));
        database.initialize();
        return database;
    }

    private static class FakeGateway implements SleeperGateway {
        boolean secondFixture;

        @Override public SleeperJsonParser.SleeperLeague fetchLeague(String leagueId) {
            return new SleeperJsonParser.SleeperLeague("L1", secondFixture ? "Updated League" : "Test League");
        }

        @Override public List<SleeperJsonParser.SleeperUser> fetchUsers(String leagueId) {
            return List.of(
                    new SleeperJsonParser.SleeperUser("u1", "Matt", "Butler Dynasty"),
                    new SleeperJsonParser.SleeperUser("u2", "Other Owner", null));
        }

        @Override public List<SleeperJsonParser.SleeperRoster> fetchRosters(String leagueId) {
            if (secondFixture) {
                return List.of(new SleeperJsonParser.SleeperRoster(
                        1, "u1", List.of("p1", "p3", "missing"), List.of(), List.of(), List.of("p3")));
            }
            return List.of(
                    new SleeperJsonParser.SleeperRoster(
                            1, "u1", List.of("p1", "p2", "p3", "missing"), List.of("p1"), List.of("p2"), List.of("p3")),
                    new SleeperJsonParser.SleeperRoster(
                            2, "u2", List.of("p1"), List.of("p1"), List.of(), List.of()));
        }

        @Override public Map<String, SleeperJsonParser.SleeperPlayer> fetchPlayers() throws IOException {
            return Map.of(
                    "p1", new SleeperJsonParser.SleeperPlayer("p1", "Player One", "QB", "CHI"),
                    "p2", new SleeperJsonParser.SleeperPlayer("p2", "Player Two", "WR", "MIN"),
                    "p3", new SleeperJsonParser.SleeperPlayer("p3", "Player Three", "RB", "DET"));
        }
    }
}
