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

class AgingModelLocalSmootherAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void poolsRawObservationsAcrossCenteredThreeAgeWindowAndKeepsEdgeSupportVisible() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        var profiles = new AgingModelPlayerProfileRepository(database);
        var production = new AgingModelPlayerSeasonProductionRepository(database);
        LocalDate asOf = LocalDate.of(2026, 9, 3);

        seed(profiles, production, "p20", LocalDate.of(2000, 1, 1), 100, 200, asOf); // +10/game
        seed(profiles, production, "p21", LocalDate.of(1999, 1, 1), 100, 300, asOf); // +20/game
        seed(profiles, production, "p22", LocalDate.of(1998, 1, 1), 100, 1100, asOf); // +100/game

        var report = new AgingModelLocalSmootherAnalyzer(database).analyze();
        var rushing = report.cells().stream()
            .filter(c -> c.position().equals("RB"))
            .filter(c -> c.metric() == AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME)
            .toList();

        assertEquals(3, rushing.size());
        var age20 = rushing.stream().filter(c -> c.age() == 20).findFirst().orElseThrow();
        var age21 = rushing.stream().filter(c -> c.age() == 21).findFirst().orElseThrow();
        var age22 = rushing.stream().filter(c -> c.age() == 22).findFirst().orElseThrow();

        assertEquals(java.util.List.of(20, 21), age20.contributingAges());
        assertEquals(2, age20.pooledObservations());
        assertEquals(15.0, age20.medianDelta());

        assertEquals(java.util.List.of(20, 21, 22), age21.contributingAges());
        assertEquals(3, age21.pooledObservations());
        assertEquals(20.0, age21.medianDelta());
        assertEquals(15.0, age21.deltaP25());
        assertEquals(60.0, age21.deltaP75());
        assertEquals(3, age21.uniquePlayers());
        assertEquals(1, age21.distinctSeasonTransitions());

        assertEquals(java.util.List.of(21, 22), age22.contributingAges());
        assertEquals(2, age22.pooledObservations());
        assertEquals(60.0, age22.medianDelta());
        assertEquals(2, report.edgeCells());
    }

    private static void seed(AgingModelPlayerProfileRepository profiles,
                             AgingModelPlayerSeasonProductionRepository production,
                             String id, LocalDate birthDate, int startRushing, int endRushing,
                             LocalDate asOf) throws Exception {
        profiles.save(new AgingModelPlayerProfile(id, id, birthDate, "RB",
            NflverseAgingModelPlayerImporter.SOURCE, asOf));
        production.save(new AgingModelPlayerSeasonProduction(id, 2020, "RB", 10,
            0, 0, 0, startRushing, 0, 0, 0, 0, 0,
            NflverseAgingModelProductionImporter.SOURCE, asOf));
        production.save(new AgingModelPlayerSeasonProduction(id, 2021, "RB", 10,
            0, 0, 0, endRushing, 0, 0, 0, 0, 0,
            NflverseAgingModelProductionImporter.SOURCE, asOf));
    }
}
