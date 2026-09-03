package io.butler.bet.intelligence;

import io.butler.bet.data.AgingModelPlayerProfileRepository;
import io.butler.bet.data.AgingModelPlayerSeasonProductionRepository;
import io.butler.bet.data.Database;
import io.butler.bet.domain.AgingModelPlayerProfile;
import io.butler.bet.domain.AgingModelPlayerSeasonProduction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgingModelSampleBreadthAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void reportsAgeGapsAndCellDensityWithoutApplyingThresholds() throws Exception {
        Database database = initialized();
        var profiles = new AgingModelPlayerProfileRepository(database);
        var production = new AgingModelPlayerSeasonProductionRepository(database);
        LocalDate asOf = LocalDate.of(2026, 9, 2);

        profiles.save(new AgingModelPlayerProfile("p1", "One", LocalDate.of(2000, 1, 1), "RB", "nflverse", asOf));
        profiles.save(new AgingModelPlayerProfile("p2", "Two", LocalDate.of(2000, 1, 1), "RB", "nflverse", asOf));

        // p1 contributes ages 20 and 23; the zero-game season blocks the intervening pairs.
        save(production, "p1", 2020, 10, 100, asOf);
        save(production, "p1", 2021, 10, 110, asOf);
        save(production, "p1", 2022, 0, 0, asOf);
        save(production, "p1", 2023, 10, 130, asOf);
        save(production, "p1", 2024, 10, 140, asOf);

        // p2 adds a second observation to age 20.
        save(production, "p2", 2020, 10, 80, asOf);
        save(production, "p2", 2021, 10, 90, asOf);

        var report = new AgingModelSampleBreadthAnalyzer(database).analyze();
        var rushing = report.dimensionBreadth().stream()
            .filter(d -> d.position().equals("RB")
                && d.metric() == AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME)
            .findFirst().orElseThrow();

        assertEquals(2, rushing.ageCells());
        assertEquals(20, rushing.minimumAge());
        assertEquals(23, rushing.maximumAge());
        assertEquals(java.util.List.of(21, 22), rushing.missingAges());
        assertEquals(3, rushing.totalObservations());
        assertEquals(1, rushing.minimumCellObservations());
        assertEquals(1.5, rushing.medianCellObservations());
        assertEquals(2, rushing.maximumCellObservations());
        assertEquals(1, rushing.singleObservationCells());
        assertEquals(1, rushing.singlePlayerCells());
        assertEquals(2, rushing.singleTransitionCells());
        assertEquals(1, rushing.maximumDistinctTransitions());
        assertEquals(50.0, rushing.ageCellCoveragePercent());
        assertTrue(report.dimensionsWithAgeGaps() > 0);
    }

    @Test
    void interpolatesMedianCellObservationCountDeterministically() {
        assertEquals(2.5, AgingModelSampleBreadthAnalyzer.percentile(java.util.List.of(1, 2, 3, 4), .5));
        assertEquals(3.0, AgingModelSampleBreadthAnalyzer.percentile(java.util.List.of(3), .5));
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        return database;
    }

    private static void save(AgingModelPlayerSeasonProductionRepository repository, String gsis, int season,
                             int games, int rushingYards, LocalDate asOf) throws Exception {
        repository.save(new AgingModelPlayerSeasonProduction(gsis, season, "RB", games,
            0, 0, 0, rushingYards, 0, 0, 0, 0, 0, "nflverse", asOf));
    }
}
