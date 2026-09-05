package io.butler.bet.data;

import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerWeekProduction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerWeekProductionRepositoryTest {
    @TempDir Path tempDir;

    @Test
    void keepsAuditableSnapshotsAndFindsLatestPerWeek() throws Exception {
        Database database = initialized();
        new PlayerRepository(database).save(new Player("p1", "1001", "Player One", "RB", "CHI"));
        var repository = new PlayerWeekProductionRepository(database);
        repository.save(new PlayerWeekProduction("old", "p1", 2025, 4,
            -3, 0, 0, 18, 0, 2, -4, 0, 1, "nflverse", LocalDate.of(2025, 10, 1)));
        repository.save(new PlayerWeekProduction("new", "p1", 2025, 4,
            7, 1, 0, -2, 0, 3, 25, 1, 0, "nflverse", LocalDate.of(2025, 10, 2)));

        var latest = repository.findLatest("p1", 2025, 4, "nflverse").orElseThrow();
        assertEquals("new", latest.id());
        assertEquals(7, latest.passingYards());
        assertEquals(-2, latest.rushingYards());
        assertEquals(25, latest.receivingYards());
        assertEquals(2, repository.findByPlayerSeason("p1", 2025, "nflverse").size());
    }

    @Test
    void sameSnapshotIdentityUpdatesStatsWithoutDuplicatingAuditDate() throws Exception {
        Database database = initialized();
        new PlayerRepository(database).save(new Player("p1", "1001", "Player One", "RB", "CHI"));
        var repository = new PlayerWeekProductionRepository(database);
        LocalDate asOf = LocalDate.of(2025, 10, 2);
        repository.save(new PlayerWeekProduction("first", "p1", 2025, 4,
            0, 0, 0, 10, 0, 1, 5, 0, 0, "nflverse", asOf));
        repository.save(new PlayerWeekProduction("replacement-id", "p1", 2025, 4,
            0, 0, 0, 12, 1, 2, 9, 0, 0, "nflverse", asOf));

        var rows = repository.findByPlayerSeason("p1", 2025, "nflverse");
        assertEquals(1, rows.size());
        assertEquals("first", rows.get(0).id());
        assertEquals(12, rows.get(0).rushingYards());
        assertEquals(1, rows.get(0).rushingTouchdowns());
    }

    @Test
    void weeklyDomainRejectsInvalidCountsButAllowsSignedYardage() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerWeekProduction(
            "id", "p1", 2025, 1, 0, -1, 0, 0, 0, 0, 0, 0, 0, "nflverse", LocalDate.now()));
        new PlayerWeekProduction(
            "id", "p1", 2025, 1, -10, 0, 0, -5, 0, 0, -8, 0, 0, "nflverse", LocalDate.now());
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        return database;
    }
}
