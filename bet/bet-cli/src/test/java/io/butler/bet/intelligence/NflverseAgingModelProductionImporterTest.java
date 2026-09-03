package io.butler.bet.intelligence;

import io.butler.bet.data.AgingModelPlayerSeasonProductionRepository;
import io.butler.bet.data.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NflverseAgingModelProductionImporterTest {
    @TempDir Path tempDir;

    @Test
    void importsGsisProductionWithoutFantasyPlayerRows() throws Exception {
        Database database = initialized();
        var importer = new NflverseAgingModelProductionImporter(database);
        var result = importer.importCsv(2025,
            statsCsv("00-0000001", 17, 100, 2, 1, 800, 7, 30, 250, 2, 0, 1, 0, 2025),
            LocalDate.of(2026, 1, 10));

        assertEquals(1, result.uniqueGsisPlayers());
        assertEquals(1, result.snapshotsWritten());
        var saved = new AgingModelPlayerSeasonProductionRepository(database)
            .findLatest("00-0000001", 2025, NflverseAgingModelProductionImporter.SOURCE).orElseThrow();
        assertEquals(17, saved.gamesPlayed());
        assertEquals(800, saved.rushingYards());
        assertEquals(1, saved.fumblesLost());
    }

    @Test
    void preservesZeroGameSeasonButPreviewDoesNotWrite() throws Exception {
        Database database = initialized();
        var importer = new NflverseAgingModelProductionImporter(database);
        var preview = importer.previewCsv(2025,
            statsCsv("00-1", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2025),
            LocalDate.of(2026, 1, 10));
        assertFalse(preview.persisted());
        assertEquals(1, preview.zeroGamePlayers());
        assertEquals(0, preview.snapshotsWritten());

        var imported = importer.importCsv(2025,
            statsCsv("00-1", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2025),
            LocalDate.of(2026, 1, 10));
        assertTrue(imported.persisted());
        assertEquals(1, imported.zeroGamePlayers());
    }

    @Test
    void rangeContinuesAfterDownloadFailureAndReportsIt() throws Exception {
        Database database = initialized();
        NflverseAgingModelProductionImporter.Downloader downloader = (uri, description) -> {
            if (uri.toString().contains("2024")) throw new IOException("missing season");
            return statsCsv("00-1", 10, 0, 0, 0, 100, 1, 5, 50, 0, 0, 0, 0, 2025);
        };
        var importer = new NflverseAgingModelProductionImporter(database, downloader,
            () -> LocalDate.of(2026, 9, 2));

        var result = importer.preview(2024, 2025);

        assertEquals(2, result.seasonsRequested());
        assertEquals(1, result.seasonsSucceeded());
        assertEquals(1, result.seasonsFailed());
        assertFalse(result.complete());
        assertEquals(2024, result.failures().getFirst().season());
        assertEquals(NflverseAgingModelProductionImporter.FailureType.DOWNLOAD,
            result.failures().getFirst().type());
    }

    @Test
    void rejectsConflictingDuplicateGsisRows() throws Exception {
        Database database = initialized();
        var importer = new NflverseAgingModelProductionImporter(database);
        String header = header();
        String row1 = row("00-1", 2025, 10, 0, 0, 0, 100, 1, 5, 50, 0, 0, 0, 0);
        String row2 = row("00-1", 2025, 10, 0, 0, 0, 200, 1, 5, 50, 0, 0, 0, 0);

        var error = assertThrows(IllegalArgumentException.class,
            () -> importer.importCsv(2025, header + row1 + row2, LocalDate.of(2026, 1, 10)));
        assertTrue(error.getMessage().contains("conflicting nflverse production rows"));
    }

    @Test
    void databaseInitializationAddsModelProductionTableToExistingDatabase() throws Exception {
        Path path = tempDir.resolve("legacy.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE leagues(id TEXT PRIMARY KEY, external_id TEXT UNIQUE, name TEXT NOT NULL)");
        }
        Database database = new Database(path);
        database.initialize();
        try (var connection = database.openConnection();
             var statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='aging_model_player_season_production'")) {
            assertTrue(rs.next());
        }
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        return database;
    }

    private static String statsCsv(String gsis, int games, int passYards, int passTds, int passInts,
                                   int rushYards, int rushTds, int receptions, int recYards, int recTds,
                                   int sackFumblesLost, int rushFumblesLost, int recFumblesLost, int season) {
        return header() + row(gsis, season, games, passYards, passTds, passInts, rushYards, rushTds,
            receptions, recYards, recTds, sackFumblesLost, rushFumblesLost, recFumblesLost);
    }

    private static String header() {
        return "player_id,season,games,passing_yards,passing_tds,passing_interceptions,rushing_yards,rushing_tds,receptions,receiving_yards,receiving_tds,sack_fumbles_lost,rushing_fumbles_lost,receiving_fumbles_lost\n";
    }

    private static String row(String gsis, int season, int games, int passYards, int passTds, int passInts,
                              int rushYards, int rushTds, int receptions, int recYards, int recTds,
                              int sackFumblesLost, int rushFumblesLost, int recFumblesLost) {
        return gsis + "," + season + "," + games + "," + passYards + "," + passTds + "," + passInts + ","
            + rushYards + "," + rushTds + "," + receptions + "," + recYards + "," + recTds + ","
            + sackFumblesLost + "," + rushFumblesLost + "," + recFumblesLost + "\n";
    }
}
