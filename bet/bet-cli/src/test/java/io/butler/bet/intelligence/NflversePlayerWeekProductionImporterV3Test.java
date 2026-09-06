package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerWeekProductionRepository;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.RawScoringProduction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NflversePlayerWeekProductionImporterV3Test {
    @TempDir Path tempDir;

    @Test
    void importsPersistsAndReplaysExactSacksSufferedAsSchemaV3() throws Exception {
        Database database = initialized("v3.db");
        new PlayerRepository(database).save(new Player("p1", "1001", "Test Quarterback", "QB", "CHI"));
        var importer = new NflversePlayerWeekProductionImporter(database);
        LocalDate asOf = LocalDate.of(2026, 1, 20);
        String stats = v3Header()
            + "00-0000001,2025,1,REG,305,2,1,15,0,0,0,0,0,0,0,1,3,0,0,0,0,4\n";
        String ids = "gsis_id,sleeper_id\n00-0000001,1001\n";

        var first = importer.importCsv(2025, stats, ids, asOf);
        var second = importer.importCsv(2025, stats, ids, asOf);

        assertEquals(1, first.snapshotsWritten());
        assertEquals(1, second.snapshotsWritten());
        var repository = new PlayerWeekProductionRepository(database);
        var persisted = repository.findLatest("p1", 2025, 1, "nflverse").orElseThrow();
        assertEquals(RawScoringProduction.SACKS_SUFFERED_SCHEMA_VERSION, persisted.rawScoringSchemaVersion());
        assertEquals(4, persisted.sacksSuffered());
        assertEquals(1, persisted.passingTwoPointConversions());
        assertEquals(3, persisted.rushingAttempts());
        assertEquals(1, repository.findByPlayerSeason("p1", 2025, "nflverse").size());
    }

    @Test
    void fullV2WithoutSacksRemainsSchemaV2AndDoesNotInventEvidence() throws Exception {
        Database database = initialized("v2.db");
        new PlayerRepository(database).save(new Player("p1", "1001", "Test Quarterback", "QB", "CHI"));
        var importer = new NflversePlayerWeekProductionImporter(database);
        String stats = v2Header()
            + "00-0000001,2025,1,REG,250,1,0,10,0,0,0,0,0,0,0,0,2,0,0,0,0\n";

        importer.importCsv(2025, stats,
            "gsis_id,sleeper_id\n00-0000001,1001\n", LocalDate.of(2026, 1, 20));

        var persisted = new PlayerWeekProductionRepository(database)
            .findLatest("p1", 2025, 1, "nflverse").orElseThrow();
        assertEquals(RawScoringProduction.EXTENDED_SCHEMA_VERSION, persisted.rawScoringSchemaVersion());
        assertEquals(0, persisted.sacksSuffered());
    }

    @Test
    void rejectsSacksColumnWithoutCompleteV2Schema() throws Exception {
        Database database = initialized("partial.db");
        new PlayerRepository(database).save(new Player("p1", "1001", "Test Quarterback", "QB", "CHI"));
        var importer = new NflversePlayerWeekProductionImporter(database);
        String stats = baseHeader() + ",sacks_suffered\n"
            + "00-0000001,2025,1,REG,250,1,0,10,0,0,0,0,0,0,0,4\n";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> importer.importCsv(
            2025, stats, "gsis_id,sleeper_id\n00-0000001,1001\n", LocalDate.of(2026, 1, 20)));

        assertTrue(error.getMessage().contains("partial nflverse raw scoring schema"));
        assertTrue(new PlayerWeekProductionRepository(database)
            .findLatest("p1", 2025, 1, "nflverse").isEmpty());
    }

    private Database initialized(String file) throws Exception {
        Database database = new Database(tempDir.resolve(file));
        database.initialize();
        return database;
    }

    private static String baseHeader() {
        return "player_id,season,week,season_type,passing_yards,passing_tds,passing_interceptions,"
            + "rushing_yards,rushing_tds,receptions,receiving_yards,receiving_tds,"
            + "sack_fumbles_lost,rushing_fumbles_lost,receiving_fumbles_lost";
    }

    private static String v2Header() {
        return baseHeader()
            + ",passing_2pt_conversions,carries,rushing_2pt_conversions,receiving_2pt_conversions,"
            + "fumble_recovery_tds,special_teams_tds\n";
    }

    private static String v3Header() {
        return baseHeader()
            + ",passing_2pt_conversions,carries,rushing_2pt_conversions,receiving_2pt_conversions,"
            + "fumble_recovery_tds,special_teams_tds,sacks_suffered\n";
    }
}
