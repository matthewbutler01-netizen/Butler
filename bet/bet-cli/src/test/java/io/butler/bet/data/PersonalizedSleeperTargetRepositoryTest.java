package io.butler.bet.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonalizedSleeperTargetRepositoryTest {
    @TempDir Path tempDir;

    @Test
    void firstBindIsVerifiedExactRepeatIsIdempotentAndConflictBlocks() throws Exception {
        Database database = database();
        PersonalizedSleeperTargetRepository repository = new PersonalizedSleeperTargetRepository(database);
        var target = target("1051699472830525440", "1312110516008677376", 6, Instant.parse("2026-09-08T07:24:00Z"));

        assertEquals(PersonalizedSleeperTargetRepository.BindState.BOUND_VERIFIED,
            repository.bindIfAbsentOrExact(target));
        assertEquals(PersonalizedSleeperTargetRepository.BindState.ALREADY_BOUND_EXACT,
            repository.bindIfAbsentOrExact(target("1051699472830525440", "1312110516008677376", 6,
                Instant.parse("2026-09-08T07:25:00Z"))));

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            repository.bindIfAbsentOrExact(target("1051614367491526656", "1312522074199162880", 1,
                Instant.parse("2026-09-08T07:26:00Z"))));
        assertEquals(true, error.getMessage().contains("explicit compare-and-set rebind is required"));
    }

    @Test
    void compareAndSetRequiresCompleteExpectedIdentity() throws Exception {
        Database database = database();
        PersonalizedSleeperTargetRepository repository = new PersonalizedSleeperTargetRepository(database);
        var original = target("1051699472830525440", "1312110516008677376", 6,
            Instant.parse("2026-09-08T07:24:00Z"));
        repository.bindIfAbsentOrExact(original);

        var wrongExpected = target("wrong", "1312110516008677376", 6,
            Instant.parse("2026-09-08T07:24:00Z"));
        var desired = target("1051699472830525440", "future", 7,
            Instant.parse("2026-09-08T07:30:00Z"));
        assertThrows(IllegalStateException.class, () -> repository.compareAndSet(wrongExpected, desired));

        assertEquals(PersonalizedSleeperTargetRepository.BindState.REBOUND_VERIFIED,
            repository.compareAndSet(original, desired));
        assertEquals("future", repository.findByButlerLeagueId("butler-hardcore").orElseThrow().sleeperLeagueId());
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(
                 "INSERT INTO leagues(id, external_id, name, season) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, "butler-hardcore");
            statement.setString(2, "1312110516008677376");
            statement.setString(3, "Hard(CORE)-Dynasty");
            statement.setInt(4, 2026);
            statement.executeUpdate();
        }
        return database;
    }

    private static PersonalizedSleeperTargetRepository.Target target(
        String userId, String sleeperLeagueId, int rosterId, Instant at) {
        return new PersonalizedSleeperTargetRepository.Target(
            "butler-hardcore", "mbutler0624", userId, sleeperLeagueId, rosterId,
            "Hard(CORE)-Dynasty", 2026, "in_season", at);
    }
}
