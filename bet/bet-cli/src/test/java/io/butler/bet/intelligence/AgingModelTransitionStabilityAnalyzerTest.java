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

class AgingModelTransitionStabilityAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void measuresMedianShiftWhenOneSeasonTransitionIsRemoved() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        var profiles = new AgingModelPlayerProfileRepository(database);
        var production = new AgingModelPlayerSeasonProductionRepository(database);
        LocalDate asOf = LocalDate.of(2026, 9, 3);

        // Two 2020->2021 observations contribute +10/game at ages 20 and 21.
        seed(profiles, production, "p20", LocalDate.of(2000, 1, 1), 2020, 100, 200, asOf);
        seed(profiles, production, "p21", LocalDate.of(1999, 1, 1), 2020, 100, 200, asOf);
        // One 2021->2022 observation contributes +100/game at age 22.
        seed(profiles, production, "p22", LocalDate.of(1999, 1, 1), 2021, 100, 1100, asOf);

        var report = new AgingModelTransitionStabilityAnalyzer(database).analyze();
        var cell = report.cells().stream()
            .filter(c -> c.position().equals("RB"))
            .filter(c -> c.metric() == AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME)
            .filter(c -> c.age() == 21)
            .findFirst().orElseThrow();

        assertEquals(3, cell.pooledObservations());
        assertEquals(2, cell.distinctSeasonTransitions());
        assertEquals(2, cell.evaluatedTransitionRemovals());
        assertEquals(0, cell.removalsWithoutRemainingSupport());
        assertEquals(10.0, cell.baselineMedianDelta());
        assertEquals(45.0, cell.medianAbsoluteShift());
        assertEquals(67.5, cell.absoluteShiftP75());
        assertEquals(90.0, cell.maximumAbsoluteShift());
        assertEquals(2020, cell.mostInfluentialStartSeason());
        assertEquals(2021, cell.mostInfluentialEndSeason());

        var dominantRemoval = report.leaveOuts().stream()
            .filter(v -> v.position().equals("RB"))
            .filter(v -> v.metric() == AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME)
            .filter(v -> v.age() == 21 && v.startSeason() == 2020)
            .findFirst().orElseThrow();
        assertEquals(100.0, dominantRemoval.leaveOutMedianDelta());
        assertEquals(90.0, dominantRemoval.absoluteShift());
        assertTrue(report.evaluatedTransitionRemovals() > 0);
    }

    private static void seed(AgingModelPlayerProfileRepository profiles,
                             AgingModelPlayerSeasonProductionRepository production,
                             String id, LocalDate birthDate, int startSeason,
                             int startRushing, int endRushing, LocalDate asOf) throws Exception {
        profiles.save(new AgingModelPlayerProfile(id, id, birthDate, "RB",
            NflverseAgingModelPlayerImporter.SOURCE, asOf));
        production.save(new AgingModelPlayerSeasonProduction(id, startSeason, "RB", 10,
            0, 0, 0, startRushing, 0, 0, 0, 0, 0,
            NflverseAgingModelProductionImporter.SOURCE, asOf));
        production.save(new AgingModelPlayerSeasonProduction(id, startSeason + 1, "RB", 10,
            0, 0, 0, endRushing, 0, 0, 0, 0, 0,
            NflverseAgingModelProductionImporter.SOURCE, asOf));
    }
}
