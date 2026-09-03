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

class AgingModelAgeBandStabilityAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void partitionsEveryCandidateThresholdByPositionAndAgeBand() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        var profiles = new AgingModelPlayerProfileRepository(database);
        var production = new AgingModelPlayerSeasonProductionRepository(database);
        LocalDate asOf = LocalDate.of(2026, 9, 3);

        seed(profiles, production, "p1", LocalDate.of(2000, 1, 1), new int[]{2020, 2021, 2022, 2023}, asOf);
        seed(profiles, production, "p2", LocalDate.of(1994, 1, 1), new int[]{2020, 2021, 2022, 2023}, asOf);
        seed(profiles, production, "p3", LocalDate.of(1988, 1, 1), new int[]{2020, 2021, 2022, 2023}, asOf);

        var report = new AgingModelAgeBandStabilityAnalyzer(database).analyze();
        assertEquals(7 * 4 * 4, report.bands().size());
        int thresholdOneCells = report.bands().stream()
            .filter(band -> band.minimumDistinctSeasonTransitions() == 1)
            .mapToInt(AgingModelAgeBandStabilityAnalyzer.AgeBandDiagnostic::retainedCells)
            .sum();
        assertTrue(thresholdOneCells > 0);
        assertTrue(thresholdOneCells <= report.normalizedCells());
        assertTrue(report.bands().stream().anyMatch(band -> band.position().equals("RB")
            && band.retainedCells() > 0));
        for (var band : report.bands()) {
            assertTrue(band.retainedCells() <= band.baselineCells());
            assertTrue(band.retainedFraction() >= 0.0 && band.retainedFraction() <= 1.0);
            if (band.baselineCells() == 0) assertEquals(0.0, band.retainedFraction());
        }

        int previous = Integer.MAX_VALUE;
        for (int threshold : new int[]{1, 3, 5, 10, 15, 20, 25}) {
            int retained = report.bands().stream()
                .filter(band -> band.minimumDistinctSeasonTransitions() == threshold)
                .mapToInt(AgingModelAgeBandStabilityAnalyzer.AgeBandDiagnostic::retainedCells)
                .sum();
            assertTrue(retained <= previous);
            previous = retained;
        }
    }

    private static void seed(AgingModelPlayerProfileRepository profiles,
                             AgingModelPlayerSeasonProductionRepository production,
                             String id, LocalDate birthDate, int[] seasons, LocalDate asOf) throws Exception {
        profiles.save(new AgingModelPlayerProfile(id, id, birthDate, "RB",
            NflverseAgingModelPlayerImporter.SOURCE, asOf));
        for (int i = 0; i < seasons.length; i++) {
            production.save(new AgingModelPlayerSeasonProduction(id, seasons[i], "RB", 10,
                0, 0, 0, 100 + (i * 150) + Math.floorMod(id.hashCode() * 31 + seasons[i] * 17, 97),
                0, 0, 0, 0, 0,
                NflverseAgingModelProductionImporter.SOURCE, asOf));
        }
    }
}
