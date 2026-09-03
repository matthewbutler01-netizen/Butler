package io.butler.bet.intelligence;

import io.butler.bet.data.AgingModelPlayerProfileRepository;
import io.butler.bet.data.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NflverseAgingModelPlayerImporterTest {
    @TempDir Path tempDir;

    @Test
    void importsBroadGsisProfilesIndependentlyOfFantasyPlayers() throws Exception {
        Database database = initialized();
        var importer = new NflverseAgingModelPlayerImporter(database);
        String csv = "gsis_id,display_name,birth_date,position,position_group\n"
            + "00-0000001,Player One,1995-01-02,QB,QB\n"
            + "00-0000002,Player Two,,WR,WR\n";

        var result = importer.importCsv(csv, LocalDate.of(2026, 9, 2));

        assertTrue(result.persisted());
        assertEquals(2, result.uniqueGsisPlayers());
        assertEquals(1, result.uniquePlayersWithBirthDate());
        assertEquals(2, result.snapshotsWritten());
        var repository = new AgingModelPlayerProfileRepository(database);
        var one = repository.findLatest("00-0000001", NflverseAgingModelPlayerImporter.SOURCE).orElseThrow();
        assertEquals(LocalDate.of(1995, 1, 2), one.birthDate());
        assertEquals("QB", one.position());
        assertTrue(repository.findLatest("00-0000002", NflverseAgingModelPlayerImporter.SOURCE).orElseThrow().birthDate() == null);
    }

    @Test
    void previewDoesNotWrite() throws Exception {
        Database database = initialized();
        var importer = new NflverseAgingModelPlayerImporter(database);
        var result = importer.previewCsv(
            "gsis_id,display_name,birth_date,position\n00-0000001,Player One,1995-01-02,RB\n",
            LocalDate.of(2026, 9, 2));

        assertFalse(result.persisted());
        assertEquals(0, result.snapshotsWritten());
        assertTrue(new AgingModelPlayerProfileRepository(database)
            .findLatest("00-0000001", NflverseAgingModelPlayerImporter.SOURCE).isEmpty());
    }

    @Test
    void preservesVersionedProviderSnapshots() throws Exception {
        Database database = initialized();
        var importer = new NflverseAgingModelPlayerImporter(database);
        String csv = "gsis_id,display_name,birth_date,position\n00-0000001,Player One,1995-01-02,RB\n";
        importer.importCsv(csv, LocalDate.of(2026, 8, 1));
        importer.importCsv(csv, LocalDate.of(2026, 9, 2));

        var repository = new AgingModelPlayerProfileRepository(database);
        assertEquals(LocalDate.of(2026, 9, 2),
            repository.findLatest("00-0000001", NflverseAgingModelPlayerImporter.SOURCE).orElseThrow().asOfDate());
        assertEquals(1, repository.findLatestBySource(NflverseAgingModelPlayerImporter.SOURCE).size());
    }

    @Test
    void rejectsMalformedBirthDateAndConflictingDuplicateGsis() throws Exception {
        Database database = initialized();
        var importer = new NflverseAgingModelPlayerImporter(database);
        assertThrows(IllegalArgumentException.class, () -> importer.importCsv(
            "gsis_id,display_name,birth_date,position\n00-1,Player,not-a-date,QB\n",
            LocalDate.of(2026, 9, 2)));

        String duplicate = "gsis_id,display_name,birth_date,position\n"
            + "00-1,Player One,1995-01-02,QB\n"
            + "00-1,Different Player,1995-01-02,QB\n";
        var error = assertThrows(IllegalArgumentException.class,
            () -> importer.importCsv(duplicate, LocalDate.of(2026, 9, 2)));
        assertTrue(error.getMessage().contains("conflicting nflverse player rows"));
    }

    @Test
    void databaseInitializationAddsModelProfileTableToExistingDatabase() throws Exception {
        Path path = tempDir.resolve("legacy.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE leagues(id TEXT PRIMARY KEY, external_id TEXT UNIQUE, name TEXT NOT NULL)");
        }

        Database database = new Database(path);
        database.initialize();
        try (var connection = database.openConnection();
             var statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='aging_model_player_profiles'")) {
            assertTrue(rs.next());
        }
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        return database;
    }
}
