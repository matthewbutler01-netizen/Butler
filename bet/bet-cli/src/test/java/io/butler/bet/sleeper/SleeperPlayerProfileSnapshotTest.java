package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerProfileSnapshotRepository;
import io.butler.bet.data.PlayerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperPlayerProfileSnapshotTest {
    @TempDir Path tempDir;

    @Test
    void parserReadsAgeAndExperienceWithoutInventingMissingValues() throws Exception {
        SleeperJsonParser parser = new SleeperJsonParser();
        var players = parser.parsePlayers("""
            {
              "p1": {"full_name":"One","position":"QB","team":"CHI","age":25,"years_exp":3},
              "p2": {"full_name":"Two","position":"WR","team":"MIN"}
            }
            """);

        assertEquals(25, players.get("p1").reportedAge());
        assertEquals(3, players.get("p1").yearsExperience());
        assertNull(players.get("p2").reportedAge());
        assertNull(players.get("p2").yearsExperience());
    }

    @Test
    void leagueImportPersistsSleeperProfileSnapshotForRosteredPlayer() throws Exception {
        Database db = new Database(tempDir.resolve("sleeper-profile.db"));
        db.initialize();
        SleeperGateway gateway = new SleeperGateway() {
            @Override public SleeperJsonParser.SleeperLeague fetchLeague(String leagueId) {
                return new SleeperJsonParser.SleeperLeague("league", "League", List.of("QB", "BN"), 2026, 2, 4);
            }
            @Override public List<SleeperJsonParser.SleeperUser> fetchUsers(String leagueId) {
                return List.of(new SleeperJsonParser.SleeperUser("u1", "Owner", "Alpha"));
            }
            @Override public List<SleeperJsonParser.SleeperRoster> fetchRosters(String leagueId) {
                return List.of(new SleeperJsonParser.SleeperRoster(1, "u1", List.of("p1"),
                    List.of("p1"), List.of(), List.of()));
            }
            @Override public Map<String, SleeperJsonParser.SleeperPlayer> fetchPlayers() {
                return Map.of("p1", new SleeperJsonParser.SleeperPlayer("p1", "Quarterback", "QB", "CHI", 24, 2));
            }
        };

        new SleeperLeagueImporter(gateway, db).importLeague("league");
        var player = new PlayerRepository(db).findByExternalId("p1").orElseThrow();
        var snapshot = new PlayerProfileSnapshotRepository(db).findLatest(player.getId(), "sleeper").orElseThrow();

        assertEquals(24, snapshot.reportedAge());
        assertEquals(2, snapshot.yearsExperience());
        assertEquals("sleeper", snapshot.source());
    }

    @Test
    void importerSkipsEmptyProviderProfileFacts() throws Exception {
        Database db = new Database(tempDir.resolve("sleeper-empty-profile.db"));
        db.initialize();
        SleeperGateway gateway = new SleeperGateway() {
            @Override public SleeperJsonParser.SleeperLeague fetchLeague(String leagueId) {
                return new SleeperJsonParser.SleeperLeague("league", "League", List.of("WR", "BN"), 2026, 2, 4);
            }
            @Override public List<SleeperJsonParser.SleeperUser> fetchUsers(String leagueId) { return List.of(); }
            @Override public List<SleeperJsonParser.SleeperRoster> fetchRosters(String leagueId) {
                return List.of(new SleeperJsonParser.SleeperRoster(1, null, List.of("p2"), List.of(), List.of(), List.of()));
            }
            @Override public Map<String, SleeperJsonParser.SleeperPlayer> fetchPlayers() {
                return Map.of("p2", new SleeperJsonParser.SleeperPlayer("p2", "Receiver", "WR", "MIN"));
            }
        };

        new SleeperLeagueImporter(gateway, db).importLeague("league");
        var player = new PlayerRepository(db).findByExternalId("p2").orElseThrow();
        assertTrue(new PlayerProfileSnapshotRepository(db).findByPlayerId(player.getId()).isEmpty());
    }
}
