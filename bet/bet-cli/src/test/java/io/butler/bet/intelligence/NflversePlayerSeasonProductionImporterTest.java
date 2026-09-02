package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.domain.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NflversePlayerSeasonProductionImporterTest {
    @TempDir Path tempDir;

    @Test
    void importsExactGsisToSleeperProductionAndTotalsLostFumbles() throws Exception {
        Database database = initialized();
        new PlayerRepository(database).save(new Player("p1", "1001", "Test Runner", "RB", "STL"));
        var importer = new NflversePlayerSeasonProductionImporter(database);

        var result = importer.importCsv(2025, statsCsv("00-0000001", 17, 12, 1, 0, 999, 8, 42, 321, 3, 0, 1, 1),
            "gsis_id,sleeper_id,name\n00-0000001,1001,Test Runner\n", LocalDate.of(2026, 1, 10));

        assertEquals(1, result.matchedPlayers());
        assertEquals(1, result.snapshotsImported());
        var saved = new PlayerSeasonProductionRepository(database).findLatest("p1", 2025, "nflverse").orElseThrow();
        assertEquals(17, saved.gamesPlayed());
        assertEquals(12, saved.passingYards());
        assertEquals(1, saved.passingTouchdowns());
        assertEquals(999, saved.rushingYards());
        assertEquals(8, saved.rushingTouchdowns());
        assertEquals(42, saved.receptions());
        assertEquals(321, saved.receivingYards());
        assertEquals(3, saved.receivingTouchdowns());
        assertEquals(2, saved.fumblesLost());
        assertEquals(LocalDate.of(2026, 1, 10), saved.asOfDate());
    }

    @Test
    void doesNotFallBackToMatchingByName() throws Exception {
        Database database = initialized();
        new PlayerRepository(database).save(new Player("p1", "1001", "Same Name", "WR", "KC"));
        var importer = new NflversePlayerSeasonProductionImporter(database);

        var result = importer.importCsv(2025, statsCsv("00-9999999", 10, 0, 0, 0, 0, 0, 20, 250, 2, 0, 0, 0),
            "gsis_id,sleeper_id,name\n00-0000001,9999,Same Name\n", LocalDate.of(2026, 1, 10));

        assertEquals(0, result.matchedPlayers());
        assertEquals(1, result.unmatchedPlayers());
        assertTrue(new PlayerSeasonProductionRepository(database).findLatest("p1", 2025, "nflverse").isEmpty());
    }

    @Test
    void rejectsAmbiguousGsisCrosswalk() throws Exception {
        Database database = initialized();
        var importer = new NflversePlayerSeasonProductionImporter(database);
        String ids = "gsis_id,sleeper_id\n00-0000001,1001\n00-0000001,1002\n";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> importer.importCsv(2025, statsCsv("00-0000001", 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), ids,
                LocalDate.of(2026, 1, 10)));
        assertTrue(error.getMessage().contains("ambiguous GSIS-to-Sleeper"));
    }

    @Test
    void ignoresOtherSeasonsAndRequiresRequestedSeasonRows() throws Exception {
        Database database = initialized();
        var importer = new NflversePlayerSeasonProductionImporter(database);
        String stats = statsCsv("00-0000001", 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2024);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> importer.importCsv(2025, stats, "gsis_id,sleeper_id\n00-0000001,1001\n", LocalDate.of(2026, 1, 10)));
        assertTrue(error.getMessage().contains("no rows for season: 2025"));
    }

    @Test
    void buildsOfficialRegularSeasonReleaseUri() {
        assertEquals("https://github.com/nflverse/nflverse-data/releases/download/stats_player/stats_player_reg_2025.csv",
            NflversePlayerSeasonProductionImporter.statsUri(2025).toString());
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        return database;
    }

    private static String statsCsv(String gsis, int games, int passYards, int passTds, int passInts,
                                   int rushYards, int rushTds, int receptions, int recYards, int recTds,
                                   int sackFumblesLost, int rushFumblesLost, int recFumblesLost) {
        return statsCsv(gsis, games, passYards, passTds, passInts, rushYards, rushTds, receptions, recYards, recTds,
            sackFumblesLost, rushFumblesLost, recFumblesLost, 2025);
    }

    private static String statsCsv(String gsis, int games, int passYards, int passTds, int passInts,
                                   int rushYards, int rushTds, int receptions, int recYards, int recTds,
                                   int sackFumblesLost, int rushFumblesLost, int recFumblesLost, int season) {
        return "player_id,season,games,passing_yards,passing_tds,passing_interceptions,rushing_yards,rushing_tds,receptions,receiving_yards,receiving_tds,sack_fumbles_lost,rushing_fumbles_lost,receiving_fumbles_lost\n"
            + gsis + "," + season + "," + games + "," + passYards + "," + passTds + "," + passInts + ","
            + rushYards + "," + rushTds + "," + receptions + "," + recYards + "," + recTds + ","
            + sackFumblesLost + "," + rushFumblesLost + "," + recFumblesLost + "\n";
    }
}
