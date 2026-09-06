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
    void migratesLegacySameDateKeyWithoutLosingExistingObservation() throws Exception {
        Database database = initialized();
        League league = new League("l1", "ext", "League", 2026);
        new LeagueRepository(database).save(league);
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE league_configuration_observations (
                    league_id TEXT NOT NULL, source TEXT NOT NULL, as_of_date TEXT NOT NULL,
                    provider_season INTEGER,
                    PRIMARY KEY (league_id, source, as_of_date),
                    FOREIGN KEY (league_id) REFERENCES leagues(id) ON DELETE CASCADE)
                """);
            statement.executeUpdate("""
                CREATE TABLE league_configuration_observation_slots (
                    league_id TEXT NOT NULL, source TEXT NOT NULL, as_of_date TEXT NOT NULL,
                    ordinal INTEGER NOT NULL, slot TEXT NOT NULL,
                    PRIMARY KEY (league_id, source, as_of_date, ordinal),
                    FOREIGN KEY (league_id, source, as_of_date)
                      REFERENCES league_configuration_observations(league_id, source, as_of_date) ON DELETE CASCADE)
                """);
            statement.executeUpdate("""
                CREATE TABLE league_configuration_observation_scoring (
                    league_id TEXT NOT NULL, source TEXT NOT NULL, as_of_date TEXT NOT NULL,
                    stat_key TEXT NOT NULL, points_per_unit REAL NOT NULL,
                    PRIMARY KEY (league_id, source, as_of_date, stat_key),
                    FOREIGN KEY (league_id, source, as_of_date)
                      REFERENCES league_configuration_observations(league_id, source, as_of_date) ON DELETE CASCADE)
                """);
            statement.executeUpdate("INSERT INTO league_configuration_observations VALUES('l1','sleeper','2026-09-06',2026)");
            statement.executeUpdate("INSERT INTO league_configuration_observation_slots VALUES('l1','sleeper','2026-09-06',0,'QB')");
            statement.executeUpdate("INSERT INTO league_configuration_observation_scoring VALUES('l1','sleeper','2026-09-06','pass_td',4.0)");
        }

        LeagueConfigurationObservationRepository repository =
            new LeagueConfigurationObservationRepository(database);
        var migrated = repository.findLatestForSeason("l1", 2026, "sleeper").orElseThrow();
        assertEquals(List.of("QB"), migrated.lineupSlots());
        assertEquals(4.0, migrated.scoringSettings().get("pass_td"));

        repository.replace(new LeagueConfigurationObservation(
            "l1", "sleeper", LocalDate.of(2026, 9, 6), 2025,
            List.of("QB", "SUPER_FLEX"), Map.of("pass_td", 6.0)));
        assertEquals(List.of("QB"),
            repository.findLatestForSeason("l1", 2026, "sleeper").orElseThrow().lineupSlots());
        assertEquals(List.of("QB", "SUPER_FLEX"),
            repository.findLatestForSeason("l1", 2025, "sleeper").orElseThrow().lineupSlots());
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
