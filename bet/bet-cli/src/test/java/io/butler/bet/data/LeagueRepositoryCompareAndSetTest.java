package io.butler.bet.data;

import io.butler.bet.domain.League;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueRepositoryCompareAndSetTest {
    @TempDir Path tempDir;

    @Test
    void updatesExternalIdAndSeasonOnlyWhenExpectedExternalIdStillMatches() throws Exception {
        Database database = new Database(tempDir.resolve("league-cas.db"));
        database.initialize();
        LeagueRepository repository = new LeagueRepository(database);
        repository.save(new League("l1", "provider-2025", "Best", 2025));

        assertFalse(repository.updateExternalIdAndSeasonIfCurrent(
            "l1", "stale-provider-id", "successor-2026", 2026));
        League unchanged = repository.findById("l1").orElseThrow();
        assertEquals("provider-2025", unchanged.getExternalId());
        assertEquals(2025, unchanged.getSeason());
        assertEquals("Best", unchanged.getName());

        assertTrue(repository.updateExternalIdAndSeasonIfCurrent(
            "l1", "provider-2025", "successor-2026", 2026));
        League updated = repository.findById("l1").orElseThrow();
        assertEquals("successor-2026", updated.getExternalId());
        assertEquals(2026, updated.getSeason());
        assertEquals("Best", updated.getName());
    }
}
