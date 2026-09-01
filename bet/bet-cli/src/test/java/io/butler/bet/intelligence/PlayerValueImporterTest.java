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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerValueImporterTest {
    @TempDir Path tempDir;

    @Test
    void importsExternalPlayerValuesAndDrivesTeamRanking() throws Exception {
        Database database = new Database(tempDir.resolve("butler.db"));
        database.initialize();
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);

        League league = new League("league", "ext-league", "Test League");
        Team alpha = new Team("alpha", "a", league.getId(), "Alpha");
        Team beta = new Team("beta", "b", league.getId(), "Beta");
        Player first = new Player("p1", "sleeper-1", "First Player", "QB", "CHI");
        Player second = new Player("p2", "sleeper-2", "Second Player", "WR", "KC");
        leagues.save(league); teams.save(alpha); teams.save(beta); players.save(first); players.save(second);
        rosters.save(new Roster("r1", null, alpha.getId(), first.getId(), "STARTER"));
        rosters.save(new Roster("r2", null, beta.getId(), second.getId(), "STARTER"));

        Path json = tempDir.resolve("values.json");
        Files.writeString(json, """
            [
              {"playerId":"sleeper-1","value":75.0,"source":"test-market","asOfDate":"2026-09-01"},
              {"playerId":"sleeper-2","value":90.0,"source":"test-market","asOfDate":"2026-09-01"},
              {"playerId":"missing-player","value":99.0,"source":"test-market","asOfDate":"2026-09-01"}
            ]
            """);

        PlayerValueImporter.ImportResult result = new PlayerValueImporter(database).importJson(json);
        assertEquals(3, result.entriesRead());
        assertEquals(2, result.imported());
        assertEquals(1, result.missingPlayers());
        assertEquals(75.0, new PlayerValueRepository(database).findLatestByPlayerId(first.getId()).orElseThrow().getValue());

        TeamStrengthAnalyzer.StrengthReport ranking = new TeamStrengthAnalyzer(database).rank(league.getId());
        assertEquals("Beta", ranking.teams().getFirst().teamName());
        assertEquals(90.0, ranking.teams().getFirst().playerValue());
        assertEquals(1, ranking.teams().getFirst().valuedPlayers());
        assertEquals(0, ranking.teams().getFirst().missingValues());
    }
}
