package io.butler.bet.data;

import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueConfigurationObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeagueConfigurationObservationObservedSeasonsTest {
    @TempDir Path tempDir;

    @Test
    void returnsDistinctKnownProviderSeasonsAcrossSourcesAndDates() throws Exception {
        Database database = new Database(tempDir.resolve("observed-seasons.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "ext", "League", 2026));
        var repository = new LeagueConfigurationObservationRepository(database);

        repository.replace(observation("sleeper", LocalDate.of(2026, 9, 1), 2025));
        repository.replace(observation("sleeper", LocalDate.of(2026, 9, 2), 2025));
        repository.replace(observation("other", LocalDate.of(2026, 9, 3), 2025));
        repository.replace(observation("sleeper", LocalDate.of(2026, 9, 4), 2026));
        repository.replace(observation("legacy", LocalDate.of(2026, 9, 5), null));

        assertEquals(List.of(2025, 2026), repository.findObservedProviderSeasons("l1"));
    }

    private static LeagueConfigurationObservation observation(
        String source, LocalDate asOfDate, Integer season) {
        return new LeagueConfigurationObservation(
            "l1", source, asOfDate, season, List.of("QB", "BN"), Map.of("pass_td", 4.0));
    }
}
