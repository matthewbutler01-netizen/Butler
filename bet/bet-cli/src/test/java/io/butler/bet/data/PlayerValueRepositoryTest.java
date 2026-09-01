package io.butler.bet.data;

import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerValueRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsHistoryAndReturnsLatestValue() throws Exception {
        Database database = database();
        PlayerRepository players = new PlayerRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);
        Player player = Player.create("Sleeper Player", "QB", "CHI");
        players.save(player);

        values.save(new PlayerValue("v1", player.getId(), 80.0, "manual", LocalDate.of(2026, 8, 1)));
        values.save(new PlayerValue("v2", player.getId(), 92.5, "manual", LocalDate.of(2026, 9, 1)));

        assertEquals(2, values.findByPlayerId(player.getId()).size());
        assertEquals(92.5, values.findLatestByPlayerId(player.getId()).orElseThrow().getValue());
        assertEquals(LocalDate.of(2026, 9, 1), values.findLatestByPlayerIdAndSource(player.getId(), "manual").orElseThrow().getAsOfDate());
    }

    @Test
    void queryInputsAreTrimmedConsistently() throws Exception {
        Database database = database();
        PlayerRepository players = new PlayerRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);
        Player player = Player.create("Player", "QB", "CHI");
        players.save(player);
        values.save(PlayerValue.create(player.getId(), 88.0, "manual", LocalDate.of(2026, 9, 1)));

        assertEquals(88.0, values.findLatestByPlayerId("  " + player.getId() + "  ").orElseThrow().getValue());
        assertEquals(88.0, values.findLatestByPlayerIdAndSource("  " + player.getId() + "  ", "  manual  ").orElseThrow().getValue());
        assertEquals(1, values.findByPlayerId("  " + player.getId() + "  ").size());
        assertEquals(1, values.findLatestBySource("  manual  ").size());
    }

    @Test
    void blankQueryInputsAreRejected() throws Exception {
        Database database = database();
        PlayerValueRepository values = new PlayerValueRepository(database);

        assertThrows(IllegalArgumentException.class, () -> values.findLatestByPlayerId("   "));
        assertThrows(IllegalArgumentException.class, () -> values.findLatestByPlayerIdAndSource("player", "   "));
        assertThrows(IllegalArgumentException.class, () -> values.findLatestBySource("   "));
        assertThrows(IllegalArgumentException.class, () -> values.deleteByPlayerId("   "));
    }

    @Test
    void upsertsSamePlayerSourceAndDate() throws Exception {
        Database database = database();
        PlayerRepository players = new PlayerRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);
        Player player = Player.create("Player", "WR", "KC");
        players.save(player);
        LocalDate date = LocalDate.of(2026, 9, 1);

        values.save(new PlayerValue("first", player.getId(), 70.0, "manual", date));
        values.save(new PlayerValue("replacement-id-is-ignored", player.getId(), 75.0, "manual", date));

        var history = values.findByPlayerId(player.getId());
        assertEquals(1, history.size());
        assertEquals(75.0, history.getFirst().getValue());
        assertEquals("first", history.getFirst().getId());
    }

    @Test
    void latestBySourceReturnsOneCurrentSnapshotPerPlayer() throws Exception {
        Database database = database();
        PlayerRepository players = new PlayerRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);
        Player first = Player.create("First", "QB", "BUF");
        Player second = Player.create("Second", "WR", "MIN");
        players.save(first);
        players.save(second);

        values.save(new PlayerValue("f-old", first.getId(), 60.0, "market", LocalDate.of(2026, 8, 1)));
        values.save(new PlayerValue("f-new", first.getId(), 95.0, "market", LocalDate.of(2026, 9, 1)));
        values.save(new PlayerValue("s-new", second.getId(), 85.0, "market", LocalDate.of(2026, 9, 1)));
        values.save(new PlayerValue("other", second.getId(), 99.0, "other", LocalDate.of(2026, 9, 1)));

        var latest = values.findLatestBySource("market");
        assertEquals(2, latest.size());
        assertEquals(first.getId(), latest.get(0).getPlayerId());
        assertEquals(95.0, latest.get(0).getValue());
        assertEquals(second.getId(), latest.get(1).getPlayerId());
    }

    @Test
    void deletingPlayerCascadesValues() throws Exception {
        Database database = database();
        PlayerRepository players = new PlayerRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);
        Player player = Player.create("Player", "RB", "DET");
        players.save(player);
        values.save(PlayerValue.create(player.getId(), 50.0, "manual", LocalDate.of(2026, 9, 1)));

        players.deleteById(player.getId());

        assertTrue(values.findByPlayerId(player.getId()).isEmpty());
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("values.db"));
        database.initialize();
        return database;
    }
}
