package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.domain.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NflverseProductionHistoryImporterTest {
    @TempDir Path tempDir;

    @Test
    void previewsInclusiveSeasonRangeWithOneCrosswalkDownloadAndNoWrites() throws Exception {
        Database database = initialized();
        new PlayerRepository(database).save(new Player("p1", "1001", "Test Runner", "RB", "STL"));
        Map<URI, String> data = baseData();
        data.put(NflversePlayerSeasonProductionImporter.statsUri(2024), statsCsv(2024, 10, 500));
        data.put(NflversePlayerSeasonProductionImporter.statsUri(2025), statsCsv(2025, 17, 900));
        AtomicInteger crosswalkDownloads = new AtomicInteger();

        var importer = new NflverseProductionHistoryImporter(database, (uri, description) -> {
            if (uri.equals(NflversePlayerSeasonProductionImporter.PLAYER_IDS_URI)) crosswalkDownloads.incrementAndGet();
            String value = data.get(uri);
            if (value == null) throw new IOException("missing: " + uri);
            return value;
        }, () -> LocalDate.of(2026, 1, 10));

        var result = importer.preview(2024, 2025);

        assertFalse(result.persisted());
        assertTrue(result.complete());
        assertEquals(2, result.seasonsRequested());
        assertEquals(2, result.seasonsSucceeded());
        assertEquals(0, result.seasonsFailed());
        assertEquals(2, result.matchedPlayerSeasons());
        assertEquals(0, result.snapshotsWritten());
        assertEquals(1, crosswalkDownloads.get());
        var repository = new PlayerSeasonProductionRepository(database);
        assertTrue(repository.findLatest("p1", 2024, "nflverse").isEmpty());
        assertTrue(repository.findLatest("p1", 2025, "nflverse").isEmpty());
    }

    @Test
    void refreshPersistsEverySuccessfulSeason() throws Exception {
        Database database = initialized();
        new PlayerRepository(database).save(new Player("p1", "1001", "Test Runner", "RB", "STL"));
        Map<URI, String> data = baseData();
        data.put(NflversePlayerSeasonProductionImporter.statsUri(2024), statsCsv(2024, 10, 500));
        data.put(NflversePlayerSeasonProductionImporter.statsUri(2025), statsCsv(2025, 17, 900));
        var importer = importer(database, data);

        var result = importer.refresh(2024, 2025);

        assertTrue(result.persisted());
        assertTrue(result.complete());
        assertEquals(2, result.snapshotsWritten());
        var repository = new PlayerSeasonProductionRepository(database);
        assertEquals(500, repository.findLatest("p1", 2024, "nflverse").orElseThrow().rushingYards());
        assertEquals(900, repository.findLatest("p1", 2025, "nflverse").orElseThrow().rushingYards());
    }

    @Test
    void reportsUnavailableSeasonAndContinuesOtherSeasons() throws Exception {
        Database database = initialized();
        new PlayerRepository(database).save(new Player("p1", "1001", "Test Runner", "RB", "STL"));
        Map<URI, String> data = baseData();
        data.put(NflversePlayerSeasonProductionImporter.statsUri(2024), statsCsv(2024, 10, 500));
        var importer = importer(database, data);

        var result = importer.refresh(2023, 2024);

        assertFalse(result.complete());
        assertEquals(1, result.seasonsSucceeded());
        assertEquals(1, result.seasonsFailed());
        assertEquals(2023, result.failures().getFirst().season());
        assertEquals(NflverseProductionHistoryImporter.FailureType.DOWNLOAD, result.failures().getFirst().type());
        assertEquals(1, result.snapshotsWritten());
        assertTrue(new PlayerSeasonProductionRepository(database)
            .findLatest("p1", 2024, "nflverse").isPresent());
    }

    @Test
    void reportsSeasonValidationFailureWithoutHidingLaterSuccess() throws Exception {
        Database database = initialized();
        new PlayerRepository(database).save(new Player("p1", "1001", "Test Runner", "RB", "STL"));
        Map<URI, String> data = baseData();
        data.put(NflversePlayerSeasonProductionImporter.statsUri(2024), "player_id,season\n00-0000001,2024\n");
        data.put(NflversePlayerSeasonProductionImporter.statsUri(2025), statsCsv(2025, 17, 900));
        var importer = importer(database, data);

        var result = importer.preview(2024, 2025);

        assertEquals(1, result.seasonsSucceeded());
        assertEquals(1, result.seasonsFailed());
        assertEquals(2024, result.failures().getFirst().season());
        assertEquals(NflverseProductionHistoryImporter.FailureType.VALIDATION, result.failures().getFirst().type());
        assertEquals(2025, result.successes().getFirst().season());
    }

    @Test
    void rejectsInvalidSeasonRangeBeforeDownloads() throws Exception {
        Database database = initialized();
        var importer = new NflverseProductionHistoryImporter(database,
            (uri, description) -> { throw new AssertionError("download should not run"); },
            () -> LocalDate.of(2026, 1, 10));

        assertThrows(IllegalArgumentException.class, () -> importer.preview(2025, 2024));
        assertThrows(IllegalArgumentException.class, () -> importer.preview(1998, 2024));
    }

    private NflverseProductionHistoryImporter importer(Database database, Map<URI, String> data) {
        return new NflverseProductionHistoryImporter(database, (uri, description) -> {
            String value = data.get(uri);
            if (value == null) throw new IOException("unavailable: " + uri);
            return value;
        }, () -> LocalDate.of(2026, 1, 10));
    }

    private Map<URI, String> baseData() {
        Map<URI, String> data = new HashMap<>();
        data.put(NflversePlayerSeasonProductionImporter.PLAYER_IDS_URI,
            "gsis_id,sleeper_id,name\n00-0000001,1001,Test Runner\n");
        return data;
    }

    private static String statsCsv(int season, int games, int rushingYards) {
        return "player_id,season,games,passing_yards,passing_tds,passing_interceptions,rushing_yards,rushing_tds,receptions,receiving_yards,receiving_tds,sack_fumbles_lost,rushing_fumbles_lost,receiving_fumbles_lost\n"
            + "00-0000001," + season + "," + games + ",0,0,0," + rushingYards + ",5,20,200,1,0,0,0\n";
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        return database;
    }
}
