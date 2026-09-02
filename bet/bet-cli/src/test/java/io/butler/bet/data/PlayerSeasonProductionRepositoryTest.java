package io.butler.bet.data;

import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerSeasonProduction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSeasonProductionRepositoryTest {
    @TempDir Path tempDir;

    @Test
    void persistsVersionedRawSeasonProductionAndFindsLatestSnapshot() throws Exception {
        Database db = new Database(tempDir.resolve("production.db"));
        db.initialize();
        Player player = Player.create("Quarterback", "QB", "CHI");
        new PlayerRepository(db).save(player);
        PlayerSeasonProductionRepository production = new PlayerSeasonProductionRepository(db);
        String source = "provider";

        production.save(PlayerSeasonProduction.create(player.getId(), 2026, 4,
            1000, 8, 2, 100, 1, 0, 0, 0, 1, source, LocalDate.of(2026, 9, 1)));
        production.save(PlayerSeasonProduction.create(player.getId(), 2026, 5,
            1300, 10, 3, 140, 2, 0, 0, 0, 1, source, LocalDate.of(2026, 9, 8)));

        var latest = production.findLatest(player.getId(), 2026, source).orElseThrow();
        assertEquals(5, latest.gamesPlayed());
        assertEquals(1300, latest.passingYards());
        assertEquals(LocalDate.of(2026, 9, 8), latest.asOfDate());
        assertEquals(2, production.findByPlayerId(player.getId()).size());
    }

    @Test
    void productionRowsCascadeWhenPlayerIsDeleted() throws Exception {
        Database db = new Database(tempDir.resolve("cascade.db"));
        db.initialize();
        Player player = Player.create("Receiver", "WR", "MIN");
        PlayerRepository players = new PlayerRepository(db);
        players.save(player);
        PlayerSeasonProductionRepository production = new PlayerSeasonProductionRepository(db);
        production.save(PlayerSeasonProduction.create(player.getId(), 2026, 3,
            0, 0, 0, 0, 0, 20, 300, 2, 0, "provider", LocalDate.of(2026, 9, 2)));

        assertTrue(players.deleteById(player.getId()));
        assertTrue(production.findByPlayerId(player.getId()).isEmpty());
    }
}
