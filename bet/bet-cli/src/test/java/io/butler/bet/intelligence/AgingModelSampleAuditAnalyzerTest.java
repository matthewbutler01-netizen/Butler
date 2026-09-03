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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgingModelSampleAuditAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void buildsExactAgeRawRateObservationsAndRobustSummary() throws Exception {
        Database database = initialized();
        saveProfile(database, "p1", "Runner One", LocalDate.of(2000, 9, 2), "RB");
        saveProduction(database, "p1", 2024, "RB", 10, 100, 10, LocalDate.of(2025, 1, 1));
        saveProduction(database, "p1", 2025, "RB", 10, 200, 20, LocalDate.of(2026, 1, 1));

        var report = new AgingModelSampleAuditAnalyzer(database).analyze();

        assertEquals(1, report.exactDobRatePairs());
        assertEquals(6, report.metricObservations()); // RB has six governed metrics.
        var rushing = report.observations().stream()
            .filter(o -> o.metric() == AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME)
            .findFirst().orElseThrow();
        assertEquals(23, rushing.age()); // birthday is after Sep 1, 2024
        assertEquals(10.0, rushing.startRate());
        assertEquals(20.0, rushing.endRate());
        assertEquals(10.0, rushing.delta());
        var cell = report.cells().stream()
            .filter(c -> c.metric() == AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME)
            .findFirst().orElseThrow();
        assertEquals(1, cell.observations());
        assertEquals(10.0, cell.medianDelta());
    }

    @Test
    void excludesZeroGameMissingDobPositionChangesAndNonconsecutiveSeasons() throws Exception {
        Database database = initialized();
        saveProfile(database, "zero", "Zero", LocalDate.of(1995, 1, 1), "RB");
        saveProduction(database, "zero", 2024, "RB", 0, 0, 0, LocalDate.of(2025, 1, 1));
        saveProduction(database, "zero", 2025, "RB", 10, 100, 0, LocalDate.of(2026, 1, 1));

        saveProfile(database, "nodob", "No Dob", null, "WR");
        saveProduction(database, "nodob", 2024, "WR", 10, 0, 100, LocalDate.of(2025, 1, 1));
        saveProduction(database, "nodob", 2025, "WR", 10, 0, 120, LocalDate.of(2026, 1, 1));

        saveProfile(database, "change", "Change", LocalDate.of(1998, 1, 1), "RB");
        saveProduction(database, "change", 2024, "RB", 10, 100, 10, LocalDate.of(2025, 1, 1));
        saveProduction(database, "change", 2025, "WR", 10, 0, 100, LocalDate.of(2026, 1, 1));

        saveProfile(database, "gap", "Gap", LocalDate.of(1997, 1, 1), "TE");
        saveProduction(database, "gap", 2023, "TE", 10, 0, 100, LocalDate.of(2024, 1, 1));
        saveProduction(database, "gap", 2025, "TE", 10, 0, 120, LocalDate.of(2026, 1, 1));

        var report = new AgingModelSampleAuditAnalyzer(database).analyze();

        assertEquals(3, report.consecutivePairs());
        assertEquals(1, report.zeroGameExcludedPairs());
        assertEquals(1, report.missingBirthDatePairs());
        assertEquals(1, report.positionChangeExcludedPairs());
        assertEquals(0, report.exactDobRatePairs());
        assertTrue(report.observations().isEmpty());
    }

    @Test
    void usesLatestSnapshotAndDoesNotPoolUnsupportedPositions() throws Exception {
        Database database = initialized();
        saveProfile(database, "p1", "Runner", LocalDate.of(1999, 1, 1), "RB");
        saveProduction(database, "p1", 2024, "RB", 10, 100, 10, LocalDate.of(2025, 1, 1));
        saveProduction(database, "p1", 2024, "RB", 10, 150, 10, LocalDate.of(2025, 2, 1));
        saveProduction(database, "p1", 2025, "RB", 10, 200, 20, LocalDate.of(2026, 1, 1));

        saveProfile(database, "k1", "Kicker", LocalDate.of(1990, 1, 1), "K");
        saveProduction(database, "k1", 2024, "K", 10, 0, 0, LocalDate.of(2025, 1, 1));
        saveProduction(database, "k1", 2025, "K", 10, 0, 0, LocalDate.of(2026, 1, 1));

        var report = new AgingModelSampleAuditAnalyzer(database).analyze();

        var rushing = report.observations().stream()
            .filter(o -> o.metric() == AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME)
            .findFirst().orElseThrow();
        assertEquals(15.0, rushing.startRate());
        assertEquals(5.0, rushing.delta());
        assertEquals(1, report.unsupportedPositionPairs());
    }

    @Test
    void calculatesDeterministicInterpolatedPercentiles() {
        List<Double> values = List.of(0.0, 10.0, 20.0, 30.0);
        assertEquals(7.5, AgingModelSampleAuditAnalyzer.percentile(values, .25));
        assertEquals(15.0, AgingModelSampleAuditAnalyzer.percentile(values, .50));
        assertEquals(22.5, AgingModelSampleAuditAnalyzer.percentile(values, .75));
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        return database;
    }

    private static void saveProfile(Database database, String gsis, String name, LocalDate dob, String position) throws Exception {
        new AgingModelPlayerProfileRepository(database).save(new AgingModelPlayerProfile(
            gsis, name, dob, position, NflverseAgingModelPlayerImporter.SOURCE, LocalDate.of(2026, 9, 2)));
    }

    private static void saveProduction(Database database, String gsis, int season, String position, int games,
                                       int rushingYards, int receivingYards, LocalDate asOf) throws Exception {
        new AgingModelPlayerSeasonProductionRepository(database).save(new AgingModelPlayerSeasonProduction(
            gsis, season, position, games, 0, 0, 0, rushingYards, 0, 0, receivingYards, 0, 0,
            NflverseAgingModelProductionImporter.SOURCE, asOf));
    }
}
