package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.HistoricalEffectiveLineupConfigurationRepository;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.domain.HistoricalEffectiveLineupConfiguration;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueConfigurationObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoricalEffectiveLineupConfigurationResolverTest {
    @TempDir Path tempDir;

    @Test
    void defaultsToRawThenUsesSeparatelyPersistedEffectiveShapeWithoutChangingScoring() throws Exception {
        Database database = database();
        var rawRepository = new LeagueConfigurationObservationRepository(database);
        rawRepository.replace(raw(RAW_AS_OF));

        var resolver = new HistoricalEffectiveLineupConfigurationResolver(database);
        var rawSelection = resolver.select("l1", 2025, "sleeper");
        assertEquals(HistoricalEffectiveLineupConfigurationResolver.State.RAW_PROVIDER_CONFIGURATION,
            rawSelection.state());
        assertEquals(RAW_FULL, rawSelection.effectiveLineupSlots());
        assertEquals(RAW_SUPPORTED, rawSelection.effectiveSupportedStartingSlots());
        assertTrue(rawSelection.derivation().isEmpty());

        var value = derivation(RAW_AS_OF, DERIVED_AS_OF);
        var repository = new HistoricalEffectiveLineupConfigurationRepository(database);
        repository.replace(value);
        assertEquals(value, repository.findLatestForSeason("l1", 2025, "sleeper").orElseThrow());

        var derived = resolver.select("l1", 2025, "sleeper");
        assertEquals(HistoricalEffectiveLineupConfigurationResolver.State.DERIVED_EFFECTIVE_CONFIGURATION,
            derived.state());
        assertEquals(EFFECTIVE_FULL, derived.effectiveLineupSlots());
        assertEquals(EFFECTIVE_SUPPORTED, derived.effectiveSupportedStartingSlots());
        assertEquals(Map.of("pass_td", 4.0), derived.rawConfiguration().scoringSettings());
        assertEquals(RAW_FULL, rawRepository.findLatestForSeason("l1", 2025, "sleeper").orElseThrow().lineupSlots());
    }

    @Test
    void failsClosedWhenRawProviderConfigurationMovesAfterDerivation() throws Exception {
        Database database = database();
        var rawRepository = new LeagueConfigurationObservationRepository(database);
        rawRepository.replace(raw(RAW_AS_OF));
        new HistoricalEffectiveLineupConfigurationRepository(database)
            .replace(derivation(RAW_AS_OF, DERIVED_AS_OF));

        rawRepository.replace(raw(RAW_AS_OF.plusDays(1)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new HistoricalEffectiveLineupConfigurationResolver(database)
                .select("l1", 2025, "sleeper"));
        assertTrue(error.getMessage().contains("derivation is stale"));
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("bf592.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "provider-current", "League One", 2025));
        return database;
    }

    private static LeagueConfigurationObservation raw(LocalDate asOf) {
        return new LeagueConfigurationObservation(
            "l1", "sleeper", asOf, 2025, RAW_FULL, Map.of("pass_td", 4.0));
    }

    private static HistoricalEffectiveLineupConfiguration derivation(LocalDate rawAsOf, LocalDate derivedAsOf) {
        return new HistoricalEffectiveLineupConfiguration(
            "l1",
            2025,
            "sleeper",
            rawAsOf,
            derivedAsOf,
            "provider-2025",
            "provider-2024",
            2024,
            7,
            "FLEX",
            RAW_SUPPORTED,
            EFFECTIVE_SUPPORTED,
            HistoricalEffectiveLineupConfigurationResolver.DERIVATION_POLICY_ID);
    }

    private static final LocalDate RAW_AS_OF = LocalDate.of(2026, 9, 6);
    private static final LocalDate DERIVED_AS_OF = LocalDate.of(2026, 9, 7);
    private static final List<String> RAW_FULL = List.of(
        "QB", "RB", "RB", "WR", "WR", "WR", "TE", "FLEX", "SUPER_FLEX", "BN", "BN");
    private static final List<String> EFFECTIVE_FULL = List.of(
        "QB", "RB", "RB", "WR", "WR", "WR", "TE", "SUPER_FLEX", "BN", "BN");
    private static final List<String> RAW_SUPPORTED = List.of(
        "QB", "RB", "RB", "WR", "WR", "WR", "TE", "FLEX", "SUPER_FLEX");
    private static final List<String> EFFECTIVE_SUPPORTED = List.of(
        "QB", "RB", "RB", "WR", "WR", "WR", "TE", "SUPER_FLEX");
}
