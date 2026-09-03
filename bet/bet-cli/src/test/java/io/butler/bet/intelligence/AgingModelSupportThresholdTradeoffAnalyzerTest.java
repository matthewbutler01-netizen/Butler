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

class AgingModelSupportThresholdTradeoffAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void higherSupportThresholdsTradeCoverageForMoreSelectiveCells() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        var profiles = new AgingModelPlayerProfileRepository(database);
        var production = new AgingModelPlayerSeasonProductionRepository(database);
        LocalDate asOf = LocalDate.of(2026, 9, 3);

        seed(profiles, production, "p1", LocalDate.of(2000, 1, 1), new int[]{2020, 2021, 2022, 2023}, asOf);
        seed(profiles, production, "p2", LocalDate.of(1999, 1, 1), new int[]{2020, 2021, 2022}, asOf);
        seed(profiles, production, "p3", LocalDate.of(1998, 1, 1), new int[]{2020, 2021, 2022}, asOf);

        var report = new AgingModelSupportThresholdTradeoffAnalyzer(database).analyze();
        assertEquals(7, report.thresholds().size());
        assertEquals(1, report.thresholds().get(0).minimumDistinctSeasonTransitions());
        assertEquals(25, report.thresholds().get(6).minimumDistinctSeasonTransitions());
        var leastRestrictive = report.thresholds().get(0);
        assertNotNull(leastRestrictive.worstCell());
        assertEquals(leastRestrictive.maximumShiftToHoldoutMae(),
            leastRestrictive.worstCell().maximumShiftToHoldoutMae());
        assertTrue(leastRestrictive.worstCell().distinctSeasonTransitions()
            >= leastRestrictive.minimumDistinctSeasonTransitions());
        for (int i = 1; i < report.thresholds().size(); i++) {
            assertTrue(report.thresholds().get(i).retainedCells() <= report.thresholds().get(i - 1).retainedCells());
            assertTrue(report.thresholds().get(i).excludedCells() >= report.thresholds().get(i - 1).excludedCells());
        }
    }

    private static void seed(AgingModelPlayerProfileRepository profiles,
                             AgingModelPlayerSeasonProductionRepository production,
                             String id, LocalDate birthDate, int[] seasons, LocalDate asOf) throws Exception {
        profiles.save(new AgingModelPlayerProfile(id, id, birthDate, "RB",
            NflverseAgingModelPlayerImporter.SOURCE, asOf));
        for (int i = 0; i < seasons.length; i++) {
            int rushingYards = 100 + (i * 150) + Math.abs(id.hashCode() % 40);
            production.save(new AgingModelPlayerSeasonProduction(id, seasons[i], "RB", 10,
                0, 0, 0, rushingYards, 0, 0, 0, 0, 0,
                NflverseAgingModelProductionImporter.SOURCE, asOf));
        }
    }
}
