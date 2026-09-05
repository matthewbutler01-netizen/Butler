package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.domain.League;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueScoringCoverageAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void completeWhenEveryNonzeroRuleMapsToStoredProduction() throws Exception {
        Database database = leagueDatabase("complete.db");
        settings(database).replace("league-1", Map.of(
            "pass_yd", 0.04,
            "pass_td", 6.0,
            "pass_int", -2.0,
            "rush_yd", 0.1,
            "rush_td", 6.0,
            "rec", 0.5,
            "rec_yd", 0.1,
            "rec_td", 6.0,
            "fum_lost", -2.0));

        var report = new LeagueScoringCoverageAnalyzer(database).analyze("league-1");

        assertEquals(LeagueScoringCoverageAnalyzer.CoverageState.COMPLETE, report.state());
        assertTrue(report.exactScoringEligible());
        assertEquals(9, report.supportedNonzeroRules());
        assertEquals(0, report.ignoredZeroRules());
        assertEquals(0, report.unsupportedNonzeroRules());
        var passing = report.rules().stream().filter(rule -> rule.statKey().equals("pass_yd")).findFirst().orElseThrow();
        assertEquals(LeagueScoringCoverageAnalyzer.RuleState.SUPPORTED, passing.state());
        assertEquals("passingYards", passing.productionField());
    }

    @Test
    void incompleteWhenAnyNonzeroRuleNeedsProductionButlerDoesNotStore() throws Exception {
        Database database = leagueDatabase("incomplete.db");
        settings(database).replace("league-1", Map.of(
            "pass_td", 6.0,
            "bonus_pass_yd_300", 3.0,
            "bonus_rec_te", 0.5));

        var report = new LeagueScoringCoverageAnalyzer(database).analyze("league-1");

        assertEquals(LeagueScoringCoverageAnalyzer.CoverageState.INCOMPLETE, report.state());
        assertFalse(report.exactScoringEligible());
        assertEquals(1, report.supportedNonzeroRules());
        assertEquals(2, report.unsupportedNonzeroRules());
        assertTrue(report.rules().stream()
            .filter(rule -> rule.statKey().equals("bonus_rec_te"))
            .allMatch(rule -> rule.state() == LeagueScoringCoverageAnalyzer.RuleState.UNSUPPORTED_NONZERO
                && rule.productionField() == null));
    }

    @Test
    void unknownZeroRuleIsExplicitlyIgnoredAndDoesNotBlockExactCoverage() throws Exception {
        Database database = leagueDatabase("zero.db");
        settings(database).replace("league-1", Map.of(
            "pass_td", 6.0,
            "future_unknown_rule", 0.0,
            "rec", 0.0));

        var report = new LeagueScoringCoverageAnalyzer(database).analyze("league-1");

        assertEquals(LeagueScoringCoverageAnalyzer.CoverageState.COMPLETE, report.state());
        assertTrue(report.exactScoringEligible());
        assertEquals(1, report.supportedNonzeroRules());
        assertEquals(2, report.ignoredZeroRules());
        var reception = report.rules().stream().filter(rule -> rule.statKey().equals("rec")).findFirst().orElseThrow();
        assertEquals(LeagueScoringCoverageAnalyzer.RuleState.ZERO_IGNORED, reception.state());
        assertNull(reception.productionField());
    }

    @Test
    void noPersistedSettingsRemainUnavailableInsteadOfAssumingDefaults() throws Exception {
        Database database = leagueDatabase("missing.db");

        var report = new LeagueScoringCoverageAnalyzer(database).analyze("league-1");

        assertEquals(LeagueScoringCoverageAnalyzer.CoverageState.NO_SCORING_SETTINGS, report.state());
        assertFalse(report.exactScoringEligible());
        assertTrue(report.rules().isEmpty());
    }

    private Database leagueDatabase(String file) throws Exception {
        Database database = new Database(tempDir.resolve(file));
        database.initialize();
        new LeagueRepository(database).save(new League("league-1", "sleeper-1", "Test League", 2026));
        return database;
    }

    private LeagueScoringSettingsRepository settings(Database database) {
        return new LeagueScoringSettingsRepository(database);
    }
}
