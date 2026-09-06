package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerWeekProductionCoverageRepository;
import io.butler.bet.data.PlayerWeekProductionRepository;
import io.butler.bet.domain.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NflversePlayerWeekProductionImporterTest {
    @TempDir Path tempDir;

    @Test
    void importsRegularSeasonWeeksWithExactIdentityAndRawStats() throws Exception {
        Database database = initialized();
        new PlayerRepository(database).save(new Player("p1", "1001", "Test Runner", "RB", "CHI"));
        var importer = new NflversePlayerWeekProductionImporter(database);
        String stats = header()
            + row("00-0000001", 2025, 1, "REG", 5, 1, 0, -2, 0, 3, 25, 1, 1, 0, 1)
            + row("00-0000001", 2025, 2, "REG", -4, 0, 1, 40, 1, 5, 51, 0, 0, 1, 0)
            + row("00-0000001", 2025, 19, "POST", 99, 4, 0, 99, 2, 9, 99, 2, 0, 0, 0);

        var result = importer.importCsv(2025, stats,
            "gsis_id,sleeper_id\n00-0000001,1001\n", LocalDate.of(2026, 1, 20));

        assertTrue(result.persisted());
        assertEquals(3, result.requestedSeasonRows());
        assertEquals(2, result.regularSeasonRows());
        assertEquals(2, result.matchedPlayerWeeks());
        assertEquals(0, result.excludedBlankPlayerRows());
        assertEquals(2, result.snapshotsWritten());
        var repository = new PlayerWeekProductionRepository(database);
        var week1 = repository.findLatest("p1", 2025, 1, "nflverse").orElseThrow();
        var week2 = repository.findLatest("p1", 2025, 2, "nflverse").orElseThrow();
        assertEquals(-2, week1.rushingYards());
        assertEquals(25, week1.receivingYards());
        assertEquals(2, week1.fumblesLost());
        assertEquals(-4, week2.passingYards());
        assertEquals(1, week2.interceptions());
        assertTrue(repository.findLatest("p1", 2025, 19, "nflverse").isEmpty());
    }

    @Test
    void previewUsesSameValidationWithoutPersisting() throws Exception {
        Database database = initialized();
        new PlayerRepository(database).save(new Player("p1", "1001", "Test Runner", "RB", "CHI"));
        var importer = new NflversePlayerWeekProductionImporter(database);

        var result = importer.previewCsv(2025,
            header() + row("00-0000001", 2025, 1, "REG", 0, 0, 0, 10, 0, 1, 5, 0, 0, 0, 0),
            "gsis_id,sleeper_id\n00-0000001,1001\n", LocalDate.of(2026, 1, 20));

        assertFalse(result.persisted());
        assertEquals(1, result.matchedPlayerWeeks());
        assertEquals(0, result.snapshotsWritten());
        assertTrue(new PlayerWeekProductionRepository(database).findLatest("p1", 2025, 1, "nflverse").isEmpty());
    }

    @Test
    void keepsUnmappedProviderRowsVisibleAndNeverMatchesByName() throws Exception {
        Database database = initialized();
        new PlayerRepository(database).save(new Player("p1", "1001", "Same Name", "WR", "CHI"));
        var importer = new NflversePlayerWeekProductionImporter(database);

        var result = importer.importCsv(2025,
            header() + row("00-9999999", 2025, 1, "REG", 0, 0, 0, 0, 0, 4, 80, 1, 0, 0, 0),
            "gsis_id,sleeper_id,name\n00-0000001,9999,Same Name\n", LocalDate.of(2026, 1, 20));

        assertEquals(0, result.matchedPlayerWeeks());
        assertEquals(1, result.unmatchedProviderRows());
        assertEquals("No GSIS-to-Sleeper mapping", result.unmatched().getFirst().reason());
        assertTrue(new PlayerWeekProductionRepository(database).findLatest("p1", 2025, 1, "nflverse").isEmpty());
    }

    @Test
    void excludesZeroProductionBlankPlayerRowsWithoutGrantingCoverage() throws Exception {
        Database database = initialized();
        new PlayerRepository(database).save(new Player("p1", "1001", "Test Runner", "RB", "CHI"));
        var importer = new NflversePlayerWeekProductionImporter(database);
        LocalDate asOfDate = LocalDate.of(2026, 1, 20);
        String stats = header()
            + row("", 2025, 1, "REG", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            + row("00-0000001", 2025, 1, "REG", 0, 0, 0, 10, 0, 1, 5, 0, 0, 0, 0);

        var result = importer.importCsv(
            2025, stats, "gsis_id,sleeper_id\n00-0000001,1001\n", asOfDate);

        assertEquals(2, result.regularSeasonRows());
        assertEquals(1, result.excludedBlankPlayerRows());
        assertEquals(1, result.matchedPlayerWeeks());
        assertEquals(0, result.unmatchedProviderRows());
        assertEquals(1, result.snapshotsWritten());
        assertTrue(new PlayerWeekProductionRepository(database)
            .findLatest("p1", 2025, 1, "nflverse").isPresent());

        var coverage = new PlayerWeekProductionCoverageRepository(database)
            .findLatest(2025, 1, "nflverse").orElseThrow();
        assertEquals(1, coverage.providerRows());
        assertEquals(1, coverage.matchedPlayerWeeks());
        assertEquals(0, coverage.unmatchedProviderRows());
        assertEquals(java.util.List.of("p1"), coverage.identityCoveredPlayerIds());
    }

    @Test
    void rejectsNonzeroProductionBlankPlayerRows() throws Exception {
        Database database = initialized();
        new PlayerRepository(database).save(new Player("p1", "1001", "Test Runner", "RB", "CHI"));
        var importer = new NflversePlayerWeekProductionImporter(database);
        String stats = header()
            + row("", 2025, 1, "REG", 0, 0, 0, 10, 0, 0, 0, 0, 0, 0, 0)
            + row("00-0000001", 2025, 1, "REG", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> importer.importCsv(
            2025, stats, "gsis_id,sleeper_id\n00-0000001,1001\n", LocalDate.of(2026, 1, 20)));

        assertTrue(error.getMessage().contains("blank nflverse player_id carries nonzero stored production"));
        assertTrue(new PlayerWeekProductionRepository(database)
            .findLatest("p1", 2025, 1, "nflverse").isEmpty());
        assertTrue(new PlayerWeekProductionCoverageRepository(database)
            .findLatest(2025, 1, "nflverse").isEmpty());
    }

    @Test
    void rejectsConflictingDuplicatePlayerWeekRows() throws Exception {
        Database database = initialized();
        new PlayerRepository(database).save(new Player("p1", "1001", "Test Runner", "RB", "CHI"));
        var importer = new NflversePlayerWeekProductionImporter(database);
        String stats = header()
            + row("00-0000001", 2025, 1, "REG", 0, 0, 0, 10, 0, 1, 5, 0, 0, 0, 0)
            + row("00-0000001", 2025, 1, "REG", 0, 0, 0, 11, 0, 1, 5, 0, 0, 0, 0);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> importer.importCsv(
            2025, stats, "gsis_id,sleeper_id\n00-0000001,1001\n", LocalDate.of(2026, 1, 20)));
        assertTrue(error.getMessage().contains("ambiguous nflverse weekly production"));
    }

    @Test
    void requiresRegularSeasonRowsAndBuildsOfficialWeeklyReleaseUri() throws Exception {
        Database database = initialized();
        var importer = new NflversePlayerWeekProductionImporter(database);
        String postseasonOnly = header()
            + row("00-0000001", 2025, 19, "POST", 0, 0, 0, 0, 0, 1, 5, 0, 0, 0, 0);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> importer.previewCsv(
            2025, postseasonOnly, "gsis_id,sleeper_id\n00-0000001,1001\n", LocalDate.of(2026, 1, 20)));
        assertTrue(error.getMessage().contains("no REG rows"));
        assertEquals(
            "https://github.com/nflverse/nflverse-data/releases/download/stats_player/stats_player_week_2025.csv",
            NflversePlayerWeekProductionImporter.statsUri(2025).toString());
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        return database;
    }

    private static String header() {
        return "player_id,season,week,season_type,passing_yards,passing_tds,passing_interceptions,rushing_yards,rushing_tds,receptions,receiving_yards,receiving_tds,sack_fumbles_lost,rushing_fumbles_lost,receiving_fumbles_lost\n";
    }

    private static String row(String playerId, int season, int week, String seasonType,
                              int passingYards, int passingTds, int interceptions,
                              int rushingYards, int rushingTds, int receptions,
                              int receivingYards, int receivingTds,
                              int sackFumblesLost, int rushingFumblesLost, int receivingFumblesLost) {
        return playerId + "," + season + "," + week + "," + seasonType + ","
            + passingYards + "," + passingTds + "," + interceptions + ","
            + rushingYards + "," + rushingTds + "," + receptions + ","
            + receivingYards + "," + receivingTds + "," + sackFumblesLost + ","
            + rushingFumblesLost + "," + receivingFumblesLost + "\n";
    }
}
