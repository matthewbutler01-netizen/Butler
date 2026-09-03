package io.butler.bet.intelligence;

import io.butler.bet.data.AgingModelPlayerSeasonProductionRepository;
import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.domain.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SignedYardageProductionTest {
    @TempDir Path tempDir;

    @Test
    void agingModelImporterPersistsLegitimateNegativeYardage() throws Exception {
        Database database = initialized("model.db");
        var importer = new NflverseAgingModelProductionImporter(database);
        String csv = header() + row("00-1", 2025, "RB", 10, -3, 0, 0, -7, 0, 2, -4, 0, 0, 0, 0);

        var result = importer.importCsv(2025, csv, LocalDate.of(2026, 1, 10));

        assertEquals(1, result.snapshotsWritten());
        var saved = new AgingModelPlayerSeasonProductionRepository(database)
            .findLatest("00-1", 2025, NflverseAgingModelProductionImporter.SOURCE).orElseThrow();
        assertEquals(-3, saved.passingYards());
        assertEquals(-7, saved.rushingYards());
        assertEquals(-4, saved.receivingYards());
    }

    @Test
    void normalNflverseImporterPersistsLegitimateNegativeYardage() throws Exception {
        Database database = initialized("normal.db");
        Player player = new Player("internal-1", "123", "Example", "RB", null);
        new PlayerRepository(database).save(player);
        var importer = new NflversePlayerSeasonProductionImporter(database);
        String stats = header() + row("00-1", 2025, "RB", 10, -3, 0, 0, -7, 0, 2, -4, 0, 0, 0, 0);
        String ids = "gsis_id,sleeper_id\n00-1,123\n";

        var result = importer.importCsv(2025, stats, ids, LocalDate.of(2026, 1, 10));

        assertEquals(1, result.snapshotsWritten());
        var saved = new PlayerSeasonProductionRepository(database)
            .findLatest(player.getId(), 2025, NflversePlayerSeasonProductionImporter.SOURCE).orElseThrow();
        assertEquals(-3, saved.passingYards());
        assertEquals(-7, saved.rushingYards());
        assertEquals(-4, saved.receivingYards());
    }

    @Test
    void initializationMigratesLegacyUnsignedYardageConstraintsWithoutLosingRows() throws Exception {
        Path path = tempDir.resolve("legacy.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE players(id TEXT PRIMARY KEY, external_id TEXT UNIQUE, display_name TEXT NOT NULL, position TEXT NOT NULL, nfl_team TEXT)");
            statement.executeUpdate("INSERT INTO players VALUES('p1','1','Legacy','RB',NULL)");
            statement.executeUpdate("""
                CREATE TABLE player_season_production (
                    id TEXT PRIMARY KEY, player_id TEXT NOT NULL, season INTEGER NOT NULL, games_played INTEGER NOT NULL,
                    passing_yards INTEGER NOT NULL, passing_touchdowns INTEGER NOT NULL, interceptions INTEGER NOT NULL,
                    rushing_yards INTEGER NOT NULL, rushing_touchdowns INTEGER NOT NULL, receptions INTEGER NOT NULL,
                    receiving_yards INTEGER NOT NULL, receiving_touchdowns INTEGER NOT NULL, fumbles_lost INTEGER NOT NULL,
                    source TEXT NOT NULL, as_of_date TEXT NOT NULL,
                    FOREIGN KEY(player_id) REFERENCES players(id) ON DELETE CASCADE,
                    UNIQUE(player_id, season, source, as_of_date),
                    CHECK(passing_yards >= 0), CHECK(rushing_yards >= 0), CHECK(receiving_yards >= 0))
                """);
            statement.executeUpdate("INSERT INTO player_season_production VALUES('old','p1',2024,1,1,0,0,1,0,0,1,0,0,'nflverse','2025-01-01')");
            statement.executeUpdate("""
                CREATE TABLE aging_model_player_season_production (
                    gsis_id TEXT NOT NULL, season INTEGER NOT NULL, position TEXT NOT NULL DEFAULT 'UNKNOWN', games_played INTEGER NOT NULL,
                    passing_yards INTEGER NOT NULL, passing_touchdowns INTEGER NOT NULL, interceptions INTEGER NOT NULL,
                    rushing_yards INTEGER NOT NULL, rushing_touchdowns INTEGER NOT NULL, receptions INTEGER NOT NULL,
                    receiving_yards INTEGER NOT NULL, receiving_touchdowns INTEGER NOT NULL, fumbles_lost INTEGER NOT NULL,
                    source TEXT NOT NULL, as_of_date TEXT NOT NULL,
                    PRIMARY KEY(gsis_id, season, source, as_of_date),
                    CHECK(passing_yards >= 0), CHECK(rushing_yards >= 0), CHECK(receiving_yards >= 0))
                """);
            statement.executeUpdate("INSERT INTO aging_model_player_season_production VALUES('g1',2024,'RB',1,1,0,0,1,0,0,1,0,0,'nflverse','2025-01-01')");
        }

        Database database = new Database(path);
        database.initialize();

        try (var connection = database.openConnection();
             var statement = connection.createStatement()) {
            var normalSql = statement.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='player_season_production'");
            normalSql.next();
            assertFalse(normalSql.getString(1).toLowerCase().contains("rushing_yards >= 0"));
            normalSql.close();
            var modelSql = statement.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='aging_model_player_season_production'");
            modelSql.next();
            assertFalse(modelSql.getString(1).toLowerCase().contains("rushing_yards >= 0"));
        }

        var normal = new PlayerSeasonProductionRepository(database)
            .findLatest("p1", 2024, "nflverse").orElseThrow();
        assertEquals(1, normal.rushingYards());
        var model = new AgingModelPlayerSeasonProductionRepository(database)
            .findLatest("g1", 2024, "nflverse").orElseThrow();
        assertEquals(1, model.rushingYards());
    }

    private Database initialized(String fileName) throws Exception {
        Database database = new Database(tempDir.resolve(fileName));
        database.initialize();
        return database;
    }

    private static String header() {
        return "player_id,season,position,games,passing_yards,passing_tds,passing_interceptions,rushing_yards,rushing_tds,receptions,receiving_yards,receiving_tds,sack_fumbles_lost,rushing_fumbles_lost,receiving_fumbles_lost\n";
    }

    private static String row(String id, int season, String position, int games, int passingYards, int passingTds,
                              int interceptions, int rushingYards, int rushingTds, int receptions,
                              int receivingYards, int receivingTds, int sackFumblesLost,
                              int rushingFumblesLost, int receivingFumblesLost) {
        return id + "," + season + "," + position + "," + games + "," + passingYards + "," + passingTds + ","
            + interceptions + "," + rushingYards + "," + rushingTds + "," + receptions + "," + receivingYards + ","
            + receivingTds + "," + sackFumblesLost + "," + rushingFumblesLost + "," + receivingFumblesLost + "\n";
    }
}
