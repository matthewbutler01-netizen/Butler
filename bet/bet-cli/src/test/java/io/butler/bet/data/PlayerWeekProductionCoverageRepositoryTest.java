package io.butler.bet.data;

import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerWeekProductionCoverage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerWeekProductionCoverageRepositoryTest {
    @TempDir Path tempDir;

    @Test
    void replacesSameSnapshotIdentityCoverageAndReturnsLatestSnapshot() throws Exception {
        Database database = initialized();
        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("p1", "1001", "One", "WR", "CHI"));
        players.save(new Player("p2", "1002", "Two", "RB", "DET"));
        PlayerWeekProductionCoverageRepository repository =
            new PlayerWeekProductionCoverageRepository(database);
        URI uri = URI.create("https://example.test/week.csv");

        repository.replace(new PlayerWeekProductionCoverage(
            2025, 4, "nflverse", uri, LocalDate.of(2026, 1, 1),
            80, 2, 1, List.of("p1", "p2")));
        repository.replace(new PlayerWeekProductionCoverage(
            2025, 4, "nflverse", uri, LocalDate.of(2026, 1, 1),
            81, 1, 2, List.of("p2")));

        var sameDay = repository.findLatest(2025, 4, "nflverse").orElseThrow();
        assertEquals(81, sameDay.providerRows());
        assertEquals(List.of("p2"), sameDay.identityCoveredPlayerIds());
        assertFalse(sameDay.coversIdentity("p1"));
        assertTrue(sameDay.coversIdentity("p2"));

        repository.replace(new PlayerWeekProductionCoverage(
            2025, 4, "nflverse", uri, LocalDate.of(2026, 1, 2),
            82, 2, 0, List.of("p1", "p2")));

        var latest = repository.findLatest(2025, 4, "nflverse").orElseThrow();
        assertEquals(LocalDate.of(2026, 1, 2), latest.asOfDate());
        assertEquals(82, latest.providerRows());
        assertEquals(List.of("p1", "p2"), latest.identityCoveredPlayerIds());
    }

    @Test
    void deletingSeasonDateRevokesOnlyThatImportDate() throws Exception {
        Database database = initialized();
        new PlayerRepository(database).save(new Player("p1", "1001", "One", "WR", "CHI"));
        PlayerWeekProductionCoverageRepository repository =
            new PlayerWeekProductionCoverageRepository(database);
        URI uri = URI.create("https://example.test/week.csv");

        repository.replace(new PlayerWeekProductionCoverage(
            2025, 1, "nflverse", uri, LocalDate.of(2026, 1, 1), 10, 1, 0, List.of("p1")));
        repository.replace(new PlayerWeekProductionCoverage(
            2025, 1, "nflverse", uri, LocalDate.of(2026, 1, 2), 11, 1, 0, List.of("p1")));

        repository.deleteBySeasonAsOf(2025, "nflverse", LocalDate.of(2026, 1, 2));

        var remaining = repository.findLatest(2025, 1, "nflverse").orElseThrow();
        assertEquals(LocalDate.of(2026, 1, 1), remaining.asOfDate());
        assertEquals(10, remaining.providerRows());
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("coverage.db"));
        database.initialize();
        return database;
    }
}
