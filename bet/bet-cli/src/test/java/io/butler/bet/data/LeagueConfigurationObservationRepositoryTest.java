package io.butler.bet.data;

import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueConfigurationObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueConfigurationObservationRepositoryTest {
    @TempDir Path tempDir;

    @Test
    void replacesSameDatePreservesSlotOrderAndReturnsLatestSeasonObservation() throws Exception {
        Database database = initialized();
        League league = new League("l1", "ext", "League", 2026);
        new LeagueRepository(database).save(league);
        LeagueConfigurationObservationRepository repository =
            new LeagueConfigurationObservationRepository(database);

        repository.replace(new LeagueConfigurationObservation(
            league.getId(), "sleeper", LocalDate.of(2026, 9, 4), 2026,
            List.of("QB", "RB", "FLEX"), scoring(1.0, 0.1)));
        repository.replace(new LeagueConfigurationObservation(
            league.getId(), "sleeper", LocalDate.of(2026, 9, 4), 2026,
            List.of("QB", "WR", "SUPER_FLEX"), scoring(1.5, 0.1)));

        var sameDate = repository.findLatestForSeason(league.getId(), 2026, "sleeper").orElseThrow();
        assertEquals(List.of("QB", "WR", "SUPER_FLEX"), sameDate.lineupSlots());
        assertEquals(1.5, sameDate.scoringSettings().get("rec"));

        repository.replace(new LeagueConfigurationObservation(
            league.getId(), "sleeper", LocalDate.of(2026, 9, 5), 2026,
            List.of("QB", "WR", "FLEX"), scoring(2.0, 0.2)));

        var latest = repository.findLatestForSeason(league.getId(), 2026, "sleeper").orElseThrow();
        assertEquals(LocalDate.of(2026, 9, 5), latest.asOfDate());
        assertEquals(List.of("QB", "WR", "FLEX"), latest.lineupSlots());
        assertEquals(2.0, latest.scoringSettings().get("rec"));
        assertTrue(repository.findLatestForSeason(league.getId(), 2025, "sleeper").isEmpty());
    }

    @Test
    void sameDateObservationsForDifferentSeasonsCoexist() throws Exception {
        Database database = initialized();
        League league = new League("l1", "ext", "League", 2026);
        new LeagueRepository(database).save(league);
        LeagueConfigurationObservationRepository repository =
            new LeagueConfigurationObservationRepository(database);
        LocalDate observed = LocalDate.of(2026, 9, 6);

        repository.replace(new LeagueConfigurationObservation(
            league.getId(), "sleeper", observed, 2026,
            List.of("QB", "RB", "FLEX"), Map.of("pass_td", 4.0)));
        repository.replace(new LeagueConfigurationObservation(
            league.getId(), "sleeper", observed, 2025,
            List.of("QB", "RB", "WR", "SUPER_FLEX"), Map.of("pass_td", 6.0)));

        var current = repository.findLatestForSeason(league.getId(), 2026, "sleeper").orElseThrow();
        var historical = repository.findLatestForSeason(league.getId(), 2025, "sleeper").orElseThrow();
        assertEquals(List.of("QB", "RB", "FLEX"), current.lineupSlots());
        assertEquals(4.0, current.scoringSettings().get("pass_td"));
        assertEquals(List.of("QB", "RB", "WR", "SUPER_FLEX"), historical.lineupSlots());
        assertEquals(6.0, historical.scoringSettings().get("pass_td"));
    }

    @Test
    void unknownProviderSeasonRemainsDistinctFromSeasonSpecificEvidence() throws Exception {
        Database database = initialized();
        League league = new League("l1", "ext", "League");
        new LeagueRepository(database).save(league);
        LeagueConfigurationObservationRepository repository =
            new LeagueConfigurationObservationRepository(database);

        repository.replace(new LeagueConfigurationObservation(
            league.getId(), "sleeper", LocalDate.of(2026, 9, 5), null,
            List.of("QB"), Map.of("pass_td", 4.0)));

        var latest = repository.findLatest(league.getId(), "sleeper").orElseThrow();
        assertEquals(null, latest.providerSeason());
        assertTrue(repository.findLatestForSeason(league.getId(), 2026, "sleeper").isEmpty());
    }

    private static Map<String, Double> scoring(double receptions, double rushYards) {
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("rec", receptions);
        result.put("rush_yd", rushYards);
        return result;
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("league-config-observation.db"));
        database.initialize();
        return database;
    }
}
