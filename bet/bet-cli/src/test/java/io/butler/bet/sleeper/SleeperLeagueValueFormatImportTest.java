package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueValueFormatRepository;
import io.butler.bet.domain.LeagueValueFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SleeperLeagueValueFormatImportTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsFormatFromSleeperRosterPositionsAndUpdatesOnReimport() throws Exception {
        Database database = new Database(tempDir.resolve("format.db"));
        database.initialize();
        FormatGateway gateway = new FormatGateway();
        SleeperLeagueImporter importer = new SleeperLeagueImporter(gateway, database);

        var first = importer.importLeague("L1");
        String leagueId = new LeagueRepository(database).findByExternalId("L1").orElseThrow().getId();
        LeagueValueFormatRepository formats = new LeagueValueFormatRepository(database);

        assertEquals(LeagueValueFormat.TWO_QB, first.valueFormat());
        assertEquals(LeagueValueFormat.TWO_QB, formats.findByLeagueId(leagueId).orElseThrow());

        gateway.superflex = false;
        var second = importer.importLeague("L1");

        assertEquals(LeagueValueFormat.ONE_QB, second.valueFormat());
        assertEquals(LeagueValueFormat.ONE_QB, formats.findByLeagueId(leagueId).orElseThrow());
    }

    private static final class FormatGateway implements SleeperGateway {
        boolean superflex = true;

        @Override public SleeperJsonParser.SleeperLeague fetchLeague(String leagueId) {
            return new SleeperJsonParser.SleeperLeague("L1", "Format League",
                superflex ? List.of("QB", "RB", "WR", "SUPER_FLEX") : List.of("QB", "RB", "WR", "FLEX"));
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
