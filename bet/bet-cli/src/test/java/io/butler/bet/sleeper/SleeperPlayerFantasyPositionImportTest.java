package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerFantasyPositionRepository;
import io.butler.bet.data.PlayerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SleeperPlayerFantasyPositionImportTest {
    @TempDir Path tempDir;

    @Test
    void parserPreservesDeclaredOrderAndDoesNotFallbackToPrimaryPosition() throws Exception {
        var players = new SleeperJsonParser().parsePlayers("""
            {
              "p1":{"full_name":"Hybrid","position":"WR","fantasy_positions":["WR","RB"]},
              "p2":{"full_name":"Quarterback","position":"QB"},
              "p3":{"full_name":"Empty","position":"TE","fantasy_positions":[]}
            }
            """);

        assertEquals(List.of("WR", "RB"), players.get("p1").fantasyPositions());
        assertEquals(List.of(), players.get("p2").fantasyPositions());
        assertEquals(List.of(), players.get("p3").fantasyPositions());
    }

    @Test
    void importPersistsExactProviderEligibilityAndClearsItWhenProviderEvidenceDisappears() throws Exception {
        Database database = new Database(tempDir.resolve("butler-test.db"));
        database.initialize();
        FakeGateway gateway = new FakeGateway();
        SleeperLeagueImporter importer = new SleeperLeagueImporter(gateway, database);
        PlayerRepository players = new PlayerRepository(database);
        PlayerFantasyPositionRepository fantasyPositions = new PlayerFantasyPositionRepository(database);

        importer.importLeague("L1");
        var p1 = players.findByExternalId("p1").orElseThrow();
        var p2 = players.findByExternalId("p2").orElseThrow();

        assertEquals("WR", p1.getPosition());
        assertEquals(List.of("WR", "RB"), fantasyPositions.findByPlayerId(p1.getId()));
        assertEquals("QB", p2.getPosition());
        assertEquals(List.of(), fantasyPositions.findByPlayerId(p2.getId()));

        gateway.omitFantasyPositions = true;
        importer.importLeague("L1");

        assertEquals("WR", players.findByExternalId("p1").orElseThrow().getPosition());
        assertEquals(List.of(), fantasyPositions.findByPlayerId(p1.getId()));
    }

    private static final class FakeGateway implements SleeperGateway {
        boolean omitFantasyPositions;

        @Override public SleeperJsonParser.SleeperLeague fetchLeague(String leagueId) {
            return new SleeperJsonParser.SleeperLeague("L1", "Test", List.of("QB", "RB", "WR", "FLEX"));
        }

        @Override public List<SleeperJsonParser.SleeperUser> fetchUsers(String leagueId) {
            return List.of();
        }

        @Override public List<SleeperJsonParser.SleeperRoster> fetchRosters(String leagueId) {
            return List.of(new SleeperJsonParser.SleeperRoster(
                1, null, List.of("p1", "p2"), List.of("p1"), List.of(), List.of()));
        }

        @Override public Map<String, SleeperJsonParser.SleeperPlayer> fetchPlayers() {
            return Map.of(
                "p1", new SleeperJsonParser.SleeperPlayer(
                    "p1", "Hybrid", "WR", "CHI", null, null,
                    omitFantasyPositions ? List.of() : List.of("WR", "RB")),
                "p2", new SleeperJsonParser.SleeperPlayer("p2", "Quarterback", "QB", "DET"));
        }
    }
}
