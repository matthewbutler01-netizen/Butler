package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueLineupConfigurationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SleeperLineupConfigurationImportTest {
    @TempDir Path tempDir;

    @Test
    void importsOrderedSleeperRosterPositionsLiterally() throws Exception {
        Database database = new Database(tempDir.resolve("lineup-import.db"));
        database.initialize();
        SleeperGateway gateway = new SleeperGateway() {
            @Override public SleeperJsonParser.SleeperLeague fetchLeague(String leagueId) {
                return new SleeperJsonParser.SleeperLeague("L1", "League",
                    List.of("QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "SUPER_FLEX", "BN", "BN", "IR", "TAXI"),
                    2026, 2, 4);
            }
            @Override public List<SleeperJsonParser.SleeperUser> fetchUsers(String leagueId) { return List.of(); }
            @Override public List<SleeperJsonParser.SleeperRoster> fetchRosters(String leagueId) { return List.of(); }
            @Override public Map<String, SleeperJsonParser.SleeperPlayer> fetchPlayers() { return Map.of(); }
        };

        var result = new SleeperLeagueImporter(gateway, database).importLeague("L1");
        assertEquals(List.of("QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "SUPER_FLEX", "BN", "BN", "IR", "TAXI"),
            new LeagueLineupConfigurationRepository(database).findByLeagueId(result.leagueId()));
    }
}
