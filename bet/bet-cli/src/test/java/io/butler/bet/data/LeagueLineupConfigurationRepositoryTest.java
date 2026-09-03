package io.butler.bet.data;

import io.butler.bet.domain.League;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeagueLineupConfigurationRepositoryTest {
    @TempDir Path tempDir;

    @Test
    void preservesOrderAndReplacesPriorConfiguration() throws Exception {
        Database database = new Database(tempDir.resolve("lineup.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "ext", "League", 2026));
        var repository = new LeagueLineupConfigurationRepository(database);

        repository.replace("l1", List.of("QB", "RB", "RB", "WR", "WR", "FLEX", "SUPER_FLEX", "BN"));
        assertEquals(List.of("QB", "RB", "RB", "WR", "WR", "FLEX", "SUPER_FLEX", "BN"),
            repository.findByLeagueId("l1"));

        repository.replace("l1", List.of("QB", "RB", "WR", "TE", "SUPER_FLEX"));
        assertEquals(List.of("QB", "RB", "WR", "TE", "SUPER_FLEX"), repository.findByLeagueId("l1"));
    }
}
