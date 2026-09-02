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
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceValueMoverAnalyzerTest {
    @TempDir
    Path tempDir;

    @Test
    void ranksPlayersByAbsoluteLatestChangeAndSkipsInsufficientHistory() throws Exception {
        Database database = database();
        PlayerRepository players = new PlayerRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);

        Player riser = Player.create("Riser", "WR", "KC");
        Player faller = Player.create("Faller", "RB", "DET");
        Player single = Player.create("Single", "QB", "BUF");
        players.save(riser);
        players.save(faller);
        players.save(single);

        values.save(PlayerValue.create(riser.getId(), 70.0, "market", LocalDate.of(2026, 8, 1)));
        values.save(PlayerValue.create(riser.getId(), 90.0, "market", LocalDate.of(2026, 9, 1)));
        values.save(PlayerValue.create(faller.getId(), 100.0, "market", LocalDate.of(2026, 8, 1)));
        values.save(PlayerValue.create(faller.getId(), 65.0, "market", LocalDate.of(2026, 9, 1)));
        values.save(PlayerValue.create(single.getId(), 88.0, "market", LocalDate.of(2026, 9, 1)));
        values.save(PlayerValue.create(riser.getId(), 999.0, "other", LocalDate.of(2026, 9, 1)));

        var report = new SourceValueMoverAnalyzer(database).analyze("  market  ");

        assertEquals("market", report.source());
        assertEquals(2, report.movers().size());
        assertEquals(faller.getId(), report.movers().get(0).playerId());
        assertEquals(-35.0, report.movers().get(0).delta());
        assertEquals(riser.getId(), report.movers().get(1).playerId());
        assertEquals(20.0, report.movers().get(1).delta());
        assertEquals(LocalDate.of(2026, 8, 1), report.movers().get(1).previousDate());
        assertEquals(LocalDate.of(2026, 9, 1), report.movers().get(1).latestDate());
    }

    @Test
    void rejectsBlankSource() throws Exception {
        Database database = database();
        assertThrows(IllegalArgumentException.class,
            () -> new SourceValueMoverAnalyzer(database).analyze("   "));
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("movers.db"));
        database.initialize();
        return database;
    }
}
