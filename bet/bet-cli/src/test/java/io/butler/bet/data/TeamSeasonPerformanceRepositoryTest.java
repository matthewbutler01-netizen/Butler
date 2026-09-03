package io.butler.bet.data;

import io.butler.bet.domain.TeamSeasonPerformance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamSeasonPerformanceRepositoryTest {
    @TempDir Path tempDir;

    @Test
    void savesAndReturnsLatestSnapshotPerTeam() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        seedLeagueAndTeams(database);
        var repository = new TeamSeasonPerformanceRepository(database);

        repository.save(new TeamSeasonPerformance("l1", "t1", 2026, 2, 1, 0, 300, 250,
            "sleeper", LocalDate.of(2026, 9, 20)));
        repository.save(new TeamSeasonPerformance("l1", "t1", 2026, 4, 1, 0, 510, 420,
            "sleeper", LocalDate.of(2026, 10, 4)));
        repository.save(new TeamSeasonPerformance("l1", "t2", 2026, 1, 4, 0, 390, 530,
            "sleeper", LocalDate.of(2026, 10, 4)));

        var latest = repository.findLatest("l1", "t1", 2026, "sleeper");
        assertTrue(latest.isPresent());
        assertEquals(4, latest.orElseThrow().wins());

        var league = repository.findLatestByLeague("l1", 2026, "sleeper");
        assertEquals(2, league.size());
        assertEquals("t1", league.get(0).teamId());
        assertEquals(LocalDate.of(2026, 10, 4), league.get(0).asOfDate());
        assertEquals("t2", league.get(1).teamId());
    }

    @Test
    void upsertsSameEvidenceIdentityWithoutDuplicatingSnapshot() throws Exception {
        Database database = new Database(tempDir.resolve("upsert.db"));
        database.initialize();
        seedLeagueAndTeams(database);
        var repository = new TeamSeasonPerformanceRepository(database);
        LocalDate asOf = LocalDate.of(2026, 9, 20);

        repository.save(new TeamSeasonPerformance("l1", "t1", 2026, 2, 1, 0, 300, 250, "sleeper", asOf));
        repository.save(new TeamSeasonPerformance("l1", "t1", 2026, 3, 1, 0, 350, 275, "sleeper", asOf));

        var league = repository.findLatestByLeague("l1", 2026, "sleeper");
        assertEquals(1, league.size());
        assertEquals(3, league.get(0).wins());
        assertEquals(350.0, league.get(0).pointsFor());
    }

    private static void seedLeagueAndTeams(Database database) throws Exception {
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO leagues(id, external_id, name, season) VALUES('l1','ext-l1','League',2026)");
            statement.executeUpdate("INSERT INTO teams(id, external_id, league_id, name) VALUES('t1','ext-t1','l1','Team 1')");
            statement.executeUpdate("INSERT INTO teams(id, external_id, league_id, name) VALUES('t2','ext-t2','l1','Team 2')");
        }
    }
}
