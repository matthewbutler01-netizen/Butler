package io.butler.bet.data;

import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerProfileRepositoryTest {
    @TempDir Path tempDir;

    @Test
    void persistsOptionalBiographicalMetadataWithoutChangingPlayerIdentity() throws Exception {
        Database db = new Database(tempDir.resolve("profiles.db"));
        db.initialize();
        Player player = Player.create("Player", "WR", "MIN");
        new PlayerRepository(db).save(player);
        PlayerProfileRepository profiles = new PlayerProfileRepository(db);

        profiles.save(new PlayerProfile(player.getId(), LocalDate.of(2000, 6, 15), 4));
        var loaded = profiles.findByPlayerId(player.getId()).orElseThrow();

        assertEquals(LocalDate.of(2000, 6, 15), loaded.birthDate());
        assertEquals(4, loaded.yearsExperience());
        assertEquals(26, loaded.ageOn(LocalDate.of(2026, 9, 2)));
        assertEquals(player.getId(), new PlayerRepository(db).findById(player.getId()).orElseThrow().getId());
    }

    @Test
    void supportsPartialProfileAndCascadeDelete() throws Exception {
        Database db = new Database(tempDir.resolve("partial.db"));
        db.initialize();
        Player player = Player.create("Player", "QB", "CHI");
        PlayerRepository players = new PlayerRepository(db);
        players.save(player);
        PlayerProfileRepository profiles = new PlayerProfileRepository(db);
        profiles.save(new PlayerProfile(player.getId(), null, 2));

        var loaded = profiles.findByPlayerId(player.getId()).orElseThrow();
        assertNull(loaded.birthDate());
        assertNull(loaded.ageOn(LocalDate.of(2026, 9, 2)));

        assertTrue(players.deleteById(player.getId()));
        assertTrue(profiles.findByPlayerId(player.getId()).isEmpty());
    }
}
