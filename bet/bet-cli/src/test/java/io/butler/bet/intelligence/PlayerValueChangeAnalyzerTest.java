package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerValueChangeAnalyzerTest {
    @TempDir
    Path tempDir;

    @Test
    void comparesLatestTwoSnapshotsForSelectedSource() throws Exception {
        Database database = database();
        PlayerRepository players = new PlayerRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);
        Player player = Player.create("Player", "WR", "CHI");
        players.save(player);

        values.save(PlayerValue.create(player.getId(), 70.0, "market", LocalDate.of(2026, 8, 1)));
        values.save(PlayerValue.create(player.getId(), 82.5, "market", LocalDate.of(2026, 8, 20)));
        values.save(PlayerValue.create(player.getId(), 90.0, "market", LocalDate.of(2026, 9, 1)));
        values.save(PlayerValue.create(player.getId(), 200.0, "other", LocalDate.of(2026, 9, 1)));

        var change = new PlayerValueChangeAnalyzer(database)
            .latestChange(player.getId(), "market").orElseThrow();

        assertEquals(LocalDate.of(2026, 8, 20), change.previousDate());
        assertEquals(82.5, change.previousValue());
        assertEquals(LocalDate.of(2026, 9, 1), change.latestDate());
        assertEquals(90.0, change.latestValue());
        assertEquals(7.5, change.delta());
    }

    @Test
    void returnsEmptyWhenFewerThanTwoSnapshotsExist() throws Exception {
        Database database = database();
        PlayerRepository players = new PlayerRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);
        Player player = Player.create("Player", "QB", "BUF");
        players.save(player);
        values.save(PlayerValue.create(player.getId(), 100.0, "market", LocalDate.of(2026, 9, 1)));

        assertTrue(new PlayerValueChangeAnalyzer(database).latestChange(player.getId(), "market").isEmpty());
        assertTrue(new PlayerValueChangeAnalyzer(database).latestChange(player.getId(), "other").isEmpty());
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("value-change.db"));
        database.initialize();
        return database;
    }
}
