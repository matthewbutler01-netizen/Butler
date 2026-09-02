package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynastyProcessLeagueStrictRefreshTest {
    @TempDir
    Path tempDir;

    @Test
    void blocksWhenSelectedLeagueHasRosterMappingGapWithoutPersisting() throws Exception {
        Database database = database("blocked.db");
        seedLeague(database, "league-a", "team-a", "p1", "100", "Mapped");
        addRosteredPlayer(database, "team-a", "p2", "200", "Missing");
        PlayerValueRepository values = new PlayerValueRepository(database);

        String ids = "fantasypros_id,sleeper_id,name,position,team\n1,100,Mapped,WR,KC\n";
        String providerValues = "player,pos,team,value_1qb,value_2qb,scrape_date,fp_id\n"
            + "Mapped,WR,KC,10,20,2026-08-28,1\n";

        assertThrows(IllegalArgumentException.class,
            () -> new DynastyProcessLeagueStrictRefresh(database).importCsv("league-a", providerValues, ids));
        assertEquals(0, values.findByPlayerId("p1").size());
    }

    @Test
    void persistsWhenSelectedLeagueIsReadyEvenIfUnrelatedLocalPlayerIsUnmatched() throws Exception {
        Database database = database("ready.db");
        seedLeague(database, "league-a", "team-a", "p1", "100", "Mapped");
        new PlayerRepository(database).save(new Player("outside", "999", "Outside", "RB", "DAL"));
        PlayerValueRepository values = new PlayerValueRepository(database);

        String ids = "fantasypros_id,sleeper_id,name,position,team\n1,100,Mapped,WR,KC\n";
        String providerValues = "player,pos,team,value_1qb,value_2qb,scrape_date,fp_id\n"
            + "Mapped,WR,KC,10,20,2026-08-28,1\n";

        var result = new DynastyProcessLeagueStrictRefresh(database).importCsv("league-a", providerValues, ids);

        assertEquals(DynastyProcessLeagueRefreshReadiness.Readiness.READY,
            DynastyProcessLeagueRefreshReadiness.classify(result.leaguePreview()));
        assertEquals(2, result.importResult().valuesImported());
        assertEquals(1, values.findByPlayerIdAndSource("p1", DynastyProcessValueImporter.SOURCE_1QB).size());
        assertEquals(0, values.findByPlayerId("outside").size());
    }

    private void seedLeague(Database database, String leagueId, String teamId, String playerId,
                            String sleeperId, String playerName) throws Exception {
        new LeagueRepository(database).save(new League(leagueId, "external-" + leagueId, "League " + leagueId));
        new TeamRepository(database).save(new Team(teamId, "external-" + teamId, leagueId, "Team " + teamId));
        addRosteredPlayer(database, teamId, playerId, sleeperId, playerName);
    }

    private void addRosteredPlayer(Database database, String teamId, String playerId,
                                   String sleeperId, String playerName) throws Exception {
        new PlayerRepository(database).save(new Player(playerId, sleeperId, playerName, "WR", "KC"));
        new RosterRepository(database).save(new Roster("roster-" + playerId, null, teamId, playerId, "STARTER"));
    }

    private Database database(String name) throws Exception {
        Database database = new Database(tempDir.resolve(name));
        database.initialize();
        return database;
    }
}
