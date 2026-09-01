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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerValueImporterTest {
    @TempDir Path tempDir;

    @Test
    void importsExternalPlayerValuesAndDrivesTeamRanking() throws Exception {
        Database database = database();
        Fixture fixture = fixture(database);
        Path json = write("values.json", """
            [
              {"externalPlayerId":"sleeper-1","value":75.0,"source":"test-market","asOfDate":"2026-09-01"},
              {"externalPlayerId":"sleeper-2","value":90.0,"source":"test-market","asOfDate":"2026-09-01"}
            ]
            """);

        PlayerValueImporter.ImportResult result = new PlayerValueImporter(database).importJson(json);
        assertEquals(2, result.valuesImported());
        assertEquals(75.0, new PlayerValueRepository(database).findLatestByPlayerIdAndSource(fixture.first().getId(), "test-market").orElseThrow().getValue());

        var ranking = new TeamStrengthAnalyzer(database).rank(fixture.league().getId(), "test-market");
        assertEquals("Beta", ranking.teams().getFirst().teamName());
        assertEquals(90.0, ranking.teams().getFirst().playerValue());
    }

    @Test
    void unknownPlayerFailsWholeImportBeforeAnyValuesAreWritten() throws Exception {
        Database database = database();
        Fixture fixture = fixture(database);
        Path json = write("unknown.json", """
            [
              {"externalPlayerId":"sleeper-1","value":75.0,"source":"market","asOfDate":"2026-09-01"},
              {"externalPlayerId":"missing-player","value":99.0,"source":"market","asOfDate":"2026-09-01"}
            ]
            """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new PlayerValueImporter(database).importJson(json));
        assertTrue(error.getMessage().contains("unknown externalPlayerId at entry 2: missing-player"));
        assertTrue(new PlayerValueRepository(database).findLatestByPlayerIdAndSource(fixture.first().getId(), "market").isEmpty());
    }

    @Test
    void invalidLaterRowFailsWholeImportBeforeAnyValuesAreWritten() throws Exception {
        Database database = database();
        Fixture fixture = fixture(database);
        Path json = write("invalid-date.json", """
            [
              {"externalPlayerId":"sleeper-1","value":75.0,"source":"market","asOfDate":"2026-09-01"},
              {"externalPlayerId":"sleeper-2","value":90.0,"source":"market","asOfDate":"not-a-date"}
            ]
            """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new PlayerValueImporter(database).importJson(json));
        assertTrue(error.getMessage().contains("invalid asOfDate for externalPlayerId sleeper-2"));
        assertTrue(new PlayerValueRepository(database).findLatestByPlayerIdAndSource(fixture.first().getId(), "market").isEmpty());
    }

    @Test
    void trimsExternalIdAndSource() throws Exception {
        Database database = database();
        Fixture fixture = fixture(database);
        Path json = write("trim.json", """
            [{"externalPlayerId":"  sleeper-1  ","value":42.0,"source":"  manual  ","asOfDate":"2026-09-01"}]
            """);

        new PlayerValueImporter(database).importJson(json);
        assertEquals(42.0, new PlayerValueRepository(database).findLatestByPlayerIdAndSource(fixture.first().getId(), "manual").orElseThrow().getValue());
    }

    @Test
    void rankingIgnoresValuesFromOtherSources() throws Exception {
        Database database = database();
        Fixture fixture = fixture(database);
        PlayerValueRepository values = new PlayerValueRepository(database);
        LocalDate date = LocalDate.of(2026, 9, 1);
        values.save(PlayerValue.create(fixture.first().getId(), 100.0, "source-a", date));
        values.save(PlayerValue.create(fixture.second().getId(), 50.0, "source-a", date));
        values.save(PlayerValue.create(fixture.first().getId(), 1.0, "source-b", date));
        values.save(PlayerValue.create(fixture.second().getId(), 999.0, "source-b", date));

        TeamStrengthAnalyzer analyzer = new TeamStrengthAnalyzer(database);
        assertEquals("Alpha", analyzer.rank(fixture.league().getId(), "source-a").teams().getFirst().teamName());
        assertEquals("Beta", analyzer.rank(fixture.league().getId(), "source-b").teams().getFirst().teamName());
    }

    private Path write(String name, String content) throws Exception {
        Path path = tempDir.resolve(name);
        Files.writeString(path, content);
        return path;
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("butler.db"));
        database.initialize();
        return database;
    }

    private Fixture fixture(Database database) throws Exception {
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
        return new Fixture(league, first, second);
    }

    private record Fixture(League league, Player first, Player second) {}
}
