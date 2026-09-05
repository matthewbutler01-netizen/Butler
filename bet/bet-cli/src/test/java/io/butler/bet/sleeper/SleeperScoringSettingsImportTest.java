package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SleeperScoringSettingsImportTest {
    @TempDir Path tempDir;

    @Test
    void leagueSyncPersistsExactScoringMapAndRemovesStaleKeysOnRefresh() throws Exception {
        Database database = new Database(tempDir.resolve("scoring-settings-import.db"));
        database.initialize();
        AtomicReference<SleeperJsonParser.SleeperLeague> source = new AtomicReference<>(league(Map.of(
            "pass_td", 6.0,
            "pass_int", -2.0,
            "rec", 0.5)));
        SleeperGateway gateway = new SleeperGateway() {
            @Override public SleeperJsonParser.SleeperLeague fetchLeague(String leagueId) { return source.get(); }
            @Override public List<SleeperJsonParser.SleeperUser> fetchUsers(String leagueId) { return List.of(); }
            @Override public List<SleeperJsonParser.SleeperRoster> fetchRosters(String leagueId) { return List.of(); }
            @Override public Map<String, SleeperJsonParser.SleeperPlayer> fetchPlayers() { return Map.of(); }
        };

        SleeperLeagueImporter importer = new SleeperLeagueImporter(gateway, database);
        var first = importer.importLeague("L1");
        LeagueScoringSettingsRepository repository = new LeagueScoringSettingsRepository(database);
        assertEquals(Map.of("pass_td", 6.0, "pass_int", -2.0, "rec", 0.5),
            repository.findByLeagueId(first.leagueId()));

        source.set(league(Map.of("pass_td", 4.0, "rec", 1.0)));
        var second = importer.importLeague("L1");
        Map<String, Double> refreshed = repository.findByLeagueId(second.leagueId());

        assertEquals(first.leagueId(), second.leagueId());
        assertEquals(Map.of("pass_td", 4.0, "rec", 1.0), refreshed);
        assertFalse(refreshed.containsKey("pass_int"));
    }

    private static SleeperJsonParser.SleeperLeague league(Map<String, Double> scoring) {
        return new SleeperJsonParser.SleeperLeague(
            "L1", "League",
            List.of("QB", "RB", "WR", "TE", "FLEX", "SUPER_FLEX", "BN"),
            2026, 2, 4, scoring);
    }
}
