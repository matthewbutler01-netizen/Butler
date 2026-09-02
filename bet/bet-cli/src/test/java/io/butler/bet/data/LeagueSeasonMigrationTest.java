package io.butler.bet.data;

import io.butler.bet.domain.League;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LeagueSeasonMigrationTest {
    @TempDir Path tempDir;

    @Test
    void initializeAddsSeasonToExistingLeagueTableWithoutLosingRows() throws Exception {
        Path path = tempDir.resolve("legacy.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE leagues (id TEXT PRIMARY KEY, external_id TEXT UNIQUE, name TEXT NOT NULL)");
            statement.executeUpdate("INSERT INTO leagues(id, external_id, name) VALUES('L1','external-1','Legacy League')");
        }

        Database database = new Database(path);
        database.initialize();
        LeagueRepository leagues = new LeagueRepository(database);
        League legacy = leagues.findById("L1").orElseThrow();
        assertEquals("Legacy League", legacy.getName());
        assertNull(legacy.getSeason());

        leagues.save(new League("L1", "external-1", "Legacy League", 2026));
        assertEquals(2026, leagues.findById("L1").orElseThrow().getSeason());
    }
}
