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
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgingModelAgeBandThresholdFrontierAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void returnsCanonicalNonDominatedCoverageInstabilityTradeoffs() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        var profiles = new AgingModelPlayerProfileRepository(database);
        var production = new AgingModelPlayerSeasonProductionRepository(database);
        LocalDate asOf = LocalDate.of(2026, 9, 3);

        seed(profiles, production, "p1", LocalDate.of(2000, 1, 1), asOf);
        seed(profiles, production, "p2", LocalDate.of(1998, 1, 1), asOf);
        seed(profiles, production, "p3", LocalDate.of(1994, 1, 1), asOf);
        seed(profiles, production, "p4", LocalDate.of(1988, 1, 1), asOf);

        var report = new AgingModelAgeBandThresholdFrontierAnalyzer(database).analyze();
        assertEquals(4 * 4, report.bands().size());
        assertTrue(report.normalizedCells() > 0);

        var rbBands = report.bands().stream()
            .filter(band -> band.position().equals("RB"))
            .filter(band -> !band.candidates().isEmpty())
            .toList();
        assertFalse(rbBands.isEmpty());

        for (var band : rbBands) {
            assertFalse(band.frontier().isEmpty());
            assertTrue(band.frontier().size() <= band.candidates().size());
            var objectives = new HashSet<String>();
            for (var frontierPoint : band.frontier()) {
                assertTrue(band.candidates().contains(frontierPoint));
                boolean dominated = band.candidates().stream()
                    .filter(other -> other != frontierPoint)
                    .anyMatch(other -> dominates(other, frontierPoint));
                assertFalse(dominated);

                String objective = frontierPoint.retainedFraction() + ":"
                    + frontierPoint.p90MaximumShiftToHoldoutMae();
                assertTrue(objectives.add(objective));

                int minimumEquivalentThreshold = band.candidates().stream()
                    .filter(candidate -> candidate.retainedFraction() == frontierPoint.retainedFraction())
                    .filter(candidate -> candidate.p90MaximumShiftToHoldoutMae()
                        == frontierPoint.p90MaximumShiftToHoldoutMae())
                    .mapToInt(AgingModelAgeBandThresholdFrontierAnalyzer.ThresholdPoint::minimumDistinctSeasonTransitions)
                    .min()
                    .orElseThrow();
                assertEquals(minimumEquivalentThreshold, frontierPoint.minimumDistinctSeasonTransitions());
            }
        }
    }

    private static boolean dominates(AgingModelAgeBandThresholdFrontierAnalyzer.ThresholdPoint left,
                                     AgingModelAgeBandThresholdFrontierAnalyzer.ThresholdPoint right) {
        boolean noWorseCoverage = left.retainedFraction() >= right.retainedFraction();
        boolean noWorseInstability = left.p90MaximumShiftToHoldoutMae() <= right.p90MaximumShiftToHoldoutMae();
        boolean strictlyBetter = left.retainedFraction() > right.retainedFraction()
            || left.p90MaximumShiftToHoldoutMae() < right.p90MaximumShiftToHoldoutMae();
        return noWorseCoverage && noWorseInstability && strictlyBetter;
    }

    private static void seed(AgingModelPlayerProfileRepository profiles,
                             AgingModelPlayerSeasonProductionRepository production,
                             String id, LocalDate birthDate, LocalDate asOf) throws Exception {
        profiles.save(new AgingModelPlayerProfile(id, id, birthDate, "RB",
            NflverseAgingModelPlayerImporter.SOURCE, asOf));
        for (int season = 2014; season <= 2025; season++) {
            int step = season - 2014;
            production.save(new AgingModelPlayerSeasonProduction(id, season, "RB", 10,
                0, 0, 0, 100 + step * 37 + Math.floorMod(id.hashCode() * 17 + season * 13, 61),
                0, 0, 0, 0, 0,
                NflverseAgingModelProductionImporter.SOURCE, asOf));
        }
    }
}
