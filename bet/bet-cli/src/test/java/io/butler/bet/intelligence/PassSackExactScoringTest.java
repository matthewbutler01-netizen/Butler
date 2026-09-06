package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.PlayerWeekProduction;
import io.butler.bet.domain.RawScoringProduction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PassSackExactScoringTest {
    @TempDir Path tempDir;

    @Test
    void scoresConfiguredPassSackPenaltyFromExactSchemaV3Count() {
        var week = PlayerWeekProduction.createExactScoringV3(
            "p1", 2025, 1,
            250, 1, 0, 10, 0, 0, 0, 0, 0,
            0, 2, 0, 0, 0, 0, 4,
            "nflverse", LocalDate.of(2026, 1, 20));

        var score = new CoveredProductionScoringPolicy().score(week, Map.of("pass_sack", -0.5));

        assertEquals(0, score.totalPoints().compareTo(new BigDecimal("-2.0")));
        assertEquals(1, score.components().size());
        assertEquals("pass_sack", score.components().getFirst().statKey());
        assertEquals(4, score.components().getFirst().rawValue());
    }

    @Test
    void schemaV2RowCannotUseItsCompatibilityZeroAsSacksEvidence() {
        var week = PlayerWeekProduction.createExactScoringV2(
            "p1", 2025, 1,
            250, 1, 0, 10, 0, 0, 0, 0, 0,
            0, 2, 0, 0, 0, 0,
            "nflverse", LocalDate.of(2026, 1, 20));

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new CoveredProductionScoringPolicy().score(week, Map.of("pass_sack", -0.5)));

        assertTrue(error.getMessage().contains("requires refreshed raw production schema v3"));
        assertEquals(0, week.sacksSuffered());
        assertEquals(RawScoringProduction.EXTENDED_SCHEMA_VERSION, week.rawScoringSchemaVersion());
    }

    @Test
    void compatibilityConstructorCannotClaimSchemaV3WithoutExplicitSacks() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new PlayerWeekProduction(
            "row", "p1", 2025, 1,
            250, 1, 0, 10, 0, 0, 0, 0, 0,
            0, 2, 0, 0, 0, 0,
            RawScoringProduction.SACKS_SUFFERED_SCHEMA_VERSION,
            "nflverse", LocalDate.of(2026, 1, 20)));

        assertTrue(error.getMessage().contains("schema v3 requires explicit sacksSuffered"));
    }

    @Test
    void passSackIsWeeklyRepresentableButSeasonCoverageRemainsFailClosed() throws Exception {
        Database database = new Database(tempDir.resolve("coverage.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("league-1", "sleeper-1", "Test League", 2025));
        new LeagueScoringSettingsRepository(database).replace("league-1", Map.of("pass_sack", -0.5));

        var analyzer = new LeagueScoringCoverageAnalyzer(database);
        var week = analyzer.analyzeWeek("league-1");
        var season = analyzer.analyze("league-1");

        assertTrue(week.exactScoringEligible());
        assertEquals(1, week.supportedNonzeroRules());
        assertFalse(season.exactScoringEligible());
        assertEquals(1, season.unsupportedNonzeroRules());
    }
}
