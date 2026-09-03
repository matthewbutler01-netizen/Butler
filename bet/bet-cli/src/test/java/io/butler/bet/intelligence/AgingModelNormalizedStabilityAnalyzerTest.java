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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgingModelNormalizedStabilityAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void normalizesRawTransitionShiftToSameDimensionHoldoutMae() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        var profiles = new AgingModelPlayerProfileRepository(database);
        var production = new AgingModelPlayerSeasonProductionRepository(database);
        LocalDate asOf = LocalDate.of(2026, 9, 3);

        // Multiple ages/transitions create both non-zero holdout error and leave-one-transition influence.
        seed(profiles, production, "p1", LocalDate.of(2000, 1, 1), new int[]{2020, 2021, 2022, 2023},
            new int[]{100, 200, 500, 900}, asOf);
        seed(profiles, production, "p2", LocalDate.of(1999, 1, 1), new int[]{2020, 2021, 2022},
            new int[]{100, 400, 500}, asOf);
        seed(profiles, production, "p3", LocalDate.of(1998, 1, 1), new int[]{2020, 2021, 2022},
            new int[]{100, 600, 800}, asOf);

        var report = new AgingModelNormalizedStabilityAnalyzer(database).analyze();
        var cell = report.cells().stream()
            .filter(c -> c.position().equals("RB"))
            .filter(c -> c.metric() == AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME)
            .filter(c -> c.maximumShiftToHoldoutMae() != null && c.maximumAbsoluteShift() > 0.0)
            .findFirst().orElseThrow();

        assertNotNull(cell.holdoutMeanAbsoluteError());
        assertTrue(cell.holdoutMeanAbsoluteError() > 0.0);
        assertEquals(cell.maximumAbsoluteShift() / cell.holdoutMeanAbsoluteError(),
            cell.maximumShiftToHoldoutMae(), 1e-12);
        assertEquals(cell.medianAbsoluteShift() / cell.holdoutMeanAbsoluteError(),
            cell.medianShiftToHoldoutMae(), 1e-12);
        assertTrue(report.normalizedCells() > 0);
    }

    private static void seed(AgingModelPlayerProfileRepository profiles,
                             AgingModelPlayerSeasonProductionRepository production,
                             String id, LocalDate birthDate, int[] seasons, int[] rushingYards,
                             LocalDate asOf) throws Exception {
        profiles.save(new AgingModelPlayerProfile(id, id, birthDate, "RB",
            NflverseAgingModelPlayerImporter.SOURCE, asOf));
        for (int i = 0; i < seasons.length; i++) {
            production.save(new AgingModelPlayerSeasonProduction(id, seasons[i], "RB", 10,
                0, 0, 0, rushingYards[i], 0, 0, 0, 0, 0,
                NflverseAgingModelProductionImporter.SOURCE, asOf));
        }
    }
}
