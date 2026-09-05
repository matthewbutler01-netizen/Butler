package io.butler.bet.data;

import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerFantasyPositionObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerFantasyPositionObservationRepositoryTest {
    @TempDir Path tempDir;

    @Test
    void preservesOrderReplacesSameDateAndReturnsLatestObservation() throws Exception {
        Database database = initialized();
        new PlayerRepository(database).save(new Player("p1", "1001", "Hybrid", "WR", "CHI"));
        PlayerFantasyPositionObservationRepository repository =
            new PlayerFantasyPositionObservationRepository(database);

        repository.replace(new PlayerFantasyPositionObservation(
            "p1", "sleeper", LocalDate.of(2026, 9, 4), List.of("WR", "RB")));
        repository.replace(new PlayerFantasyPositionObservation(
            "p1", "sleeper", LocalDate.of(2026, 9, 4), List.of("RB", "WR")));

        var sameDate = repository.findLatest("p1", "sleeper").orElseThrow();
        assertEquals(LocalDate.of(2026, 9, 4), sameDate.asOfDate());
        assertEquals(List.of("RB", "WR"), sameDate.providerFantasyPositions());

        repository.replace(new PlayerFantasyPositionObservation(
            "p1", "sleeper", LocalDate.of(2026, 9, 5), List.of("WR")));

        var latest = repository.findLatest("p1", "sleeper").orElseThrow();
        assertEquals(LocalDate.of(2026, 9, 5), latest.asOfDate());
        assertEquals(List.of("WR"), latest.providerFantasyPositions());
    }

    @Test
    void explicitEmptyEligibilityIsStillAnObservedSnapshot() throws Exception {
        Database database = initialized();
        new PlayerRepository(database).save(new Player("p1", "1001", "Unknown Eligibility", "WR", "CHI"));
        PlayerFantasyPositionObservationRepository repository =
            new PlayerFantasyPositionObservationRepository(database);

        repository.replace(new PlayerFantasyPositionObservation(
            "p1", "sleeper", LocalDate.of(2026, 9, 5), List.of()));

        var latest = repository.findLatest("p1", "sleeper").orElseThrow();
        assertTrue(latest.providerFantasyPositions().isEmpty());
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("fantasy-position-observation.db"));
        database.initialize();
        return database;
    }
}
