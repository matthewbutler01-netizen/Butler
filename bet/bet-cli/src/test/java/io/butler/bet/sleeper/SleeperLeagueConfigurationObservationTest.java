package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SleeperLeagueConfigurationObservationTest {
    @TempDir Path tempDir;

    @Test
    void snapshotsProviderSeasonLineupOrderAndScoringTogether() throws Exception {
        Database database = new Database(tempDir.resolve("league-config-import.db"));
        database.initialize();
        var result = new SleeperLeagueImporter(new FakeGateway(), database).importLeague("L1");

        var observation = new LeagueConfigurationObservationRepository(database)
            .findLatestForSeason(result.leagueId(), 2026, "sleeper").orElseThrow();

        assertEquals(2026, observation.providerSeason());
        assertEquals(List.of("QB", "RB", "WR", "FLEX", "SUPER_FLEX", "BN"),
            observation.lineupSlots());
        assertEquals(1.0, observation.scoringSettings().get("rec"));
        assertEquals(0.1, observation.scoringSettings().get("rush_yd"));
        assertEquals(4.0, observation.scoringSettings().get("pass_td"));
    }

    private static final class FakeGateway implements SleeperGateway {
        @Override public SleeperJsonParser.SleeperLeague fetchLeague(String leagueId) {
            Map<String, Double> scoring = new LinkedHashMap<>();
            scoring.put("rec", 1.0);
            scoring.put("rush_yd", 0.1);
            scoring.put("pass_td", 4.0);
            return new SleeperJsonParser.SleeperLeague(
                "L1", "League", List.of("QB", "RB", "WR", "FLEX", "SUPER_FLEX", "BN"),
                2026, 2, 4, scoring);
        }

        @Override public List<SleeperJsonParser.SleeperUser> fetchUsers(String leagueId) {
            return List.of();
        }

        @Override public List<SleeperJsonParser.SleeperRoster> fetchRosters(String leagueId) {
            return List.of();
        }

        @Override public Map<String, SleeperJsonParser.SleeperPlayer> fetchPlayers() {
            return Map.of();
        }
    }
}
