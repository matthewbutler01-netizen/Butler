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

class AgingModelTemporalHoldoutAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void evaluatesEachTransitionUsingOnlyEarlierTransitions() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        var profiles = new AgingModelPlayerProfileRepository(database);
        var production = new AgingModelPlayerSeasonProductionRepository(database);
        LocalDate asOf = LocalDate.of(2026, 9, 3);

        profiles.save(new AgingModelPlayerProfile("p1", "One", LocalDate.of(2000, 1, 1), "RB",
            NflverseAgingModelPlayerImporter.SOURCE, asOf));
        save(production, "p1", 2020, 100, asOf); // 10/game
        save(production, "p1", 2021, 200, asOf); // 20/game: +10
        save(production, "p1", 2022, 400, asOf); // 40/game: +20
        save(production, "p1", 2023, 700, asOf); // 70/game: +30

        var report = new AgingModelTemporalHoldoutAnalyzer(database).analyze();
        var rushing = report.observations().stream()
            .filter(o -> o.position().equals("RB"))
            .filter(o -> o.metric() == AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME)
            .toList();

        assertEquals(2, rushing.size());
        var first = rushing.stream().filter(o -> o.endSeason() == 2022).findFirst().orElseThrow();
        assertEquals(20.0, first.observedDelta());
        assertEquals(10.0, first.predictedMedianDelta());
        assertEquals(10.0, first.absoluteError());
        assertEquals(1, first.trainingObservations());
        assertEquals(java.util.List.of(20), first.trainingAges());

        var second = rushing.stream().filter(o -> o.endSeason() == 2023).findFirst().orElseThrow();
        assertEquals(30.0, second.observedDelta());
        assertEquals(20.0, second.predictedMedianDelta());
        assertEquals(10.0, second.absoluteError());
        assertEquals(1, second.trainingObservations());
        assertEquals(java.util.List.of(21), second.trainingAges());

        var dimension = report.dimensions().stream()
            .filter(d -> d.position().equals("RB"))
            .filter(d -> d.metric() == AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME)
            .findFirst().orElseThrow();
        assertEquals(2, dimension.evaluatedObservations());
        assertEquals(10.0, dimension.meanAbsoluteError());
        assertEquals(10.0, dimension.medianAbsoluteError());
        assertTrue(report.observationsWithoutPriorTraining() > 0);
    }

    private static void save(AgingModelPlayerSeasonProductionRepository repository, String id,
                             int season, int rushingYards, LocalDate asOf) throws Exception {
        repository.save(new AgingModelPlayerSeasonProduction(id, season, "RB", 10,
            0, 0, 0, rushingYards, 0, 0, 0, 0, 0,
            NflverseAgingModelProductionImporter.SOURCE, asOf));
    }
}
