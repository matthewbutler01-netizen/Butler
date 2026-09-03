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

class AgingModelSmoothingSensitivityAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void comparesLocalAndExactAgePredictionsOnlyWhenBothHavePriorHistory() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        var profiles = new AgingModelPlayerProfileRepository(database);
        var production = new AgingModelPlayerSeasonProductionRepository(database);
        LocalDate asOf = LocalDate.of(2026, 9, 3);

        // pA supplies an age-20 +30 observation, followed by an age-21 +30 test observation.
        profile(profiles, "pA", LocalDate.of(2000, 1, 1), asOf);
        season(production, "pA", 2020, 100, asOf);
        season(production, "pA", 2021, 400, asOf);
        season(production, "pA", 2022, 700, asOf);

        // pB supplies exact age-21 +10 history before pA's age-21 test transition.
        profile(profiles, "pB", LocalDate.of(1999, 1, 1), asOf);
        season(production, "pB", 2020, 100, asOf);
        season(production, "pB", 2021, 200, asOf);

        // pC supplies neighboring age-22 +50 history before pA's age-21 test transition.
        profile(profiles, "pC", LocalDate.of(1998, 1, 1), asOf);
        season(production, "pC", 2020, 100, asOf);
        season(production, "pC", 2021, 600, asOf);

        // pD first appears at age 23; local has nearby prior age-22 history but center has none.
        profile(profiles, "pD", LocalDate.of(1997, 1, 1), asOf);
        season(production, "pD", 2020, 100, asOf);
        season(production, "pD", 2021, 400, asOf);

        var report = new AgingModelSmoothingSensitivityAnalyzer(database).analyze();
        var rushing = report.observations().stream()
            .filter(o -> o.position().equals("RB"))
            .filter(o -> o.metric() == AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME)
            .toList();

        // pA's 2021->2022 observed delta is +30/game. Earlier local training contains
        // age-20 +30, age-21 +10, and age-22 +50, whose median is +30. Exact-age-only is +10.
        var paired = rushing.stream()
            .filter(o -> o.gsisId().equals("pA") && o.endSeason() == 2022)
            .findFirst().orElseThrow();
        assertEquals(30.0, paired.observedDelta());
        assertEquals(30.0, paired.localPrediction());
        assertEquals(10.0, paired.centerPrediction());
        assertEquals(0.0, paired.localAbsoluteError());
        assertEquals(20.0, paired.centerAbsoluteError());
        assertEquals(-20.0, paired.absoluteErrorDifference());
        assertEquals(3, paired.localTrainingObservations());
        assertEquals(1, paired.centerTrainingObservations());

        var dimension = report.dimensions().stream()
            .filter(d -> d.position().equals("RB"))
            .filter(d -> d.metric() == AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME)
            .findFirst().orElseThrow();
        assertTrue(dimension.localWins() > 0);
        assertTrue(report.localAvailableCenterUnavailable() > 0);
        assertTrue(report.neitherAvailable() > 0);
    }

    private static void profile(AgingModelPlayerProfileRepository repository, String id,
                                LocalDate birthDate, LocalDate asOf) throws Exception {
        repository.save(new AgingModelPlayerProfile(id, id, birthDate, "RB",
            NflverseAgingModelPlayerImporter.SOURCE, asOf));
    }

    private static void season(AgingModelPlayerSeasonProductionRepository repository, String id,
                               int season, int rushingYards, LocalDate asOf) throws Exception {
        repository.save(new AgingModelPlayerSeasonProduction(id, season, "RB", 10,
            0, 0, 0, rushingYards, 0, 0, 0, 0, 0,
            NflverseAgingModelProductionImporter.SOURCE, asOf));
    }
}
