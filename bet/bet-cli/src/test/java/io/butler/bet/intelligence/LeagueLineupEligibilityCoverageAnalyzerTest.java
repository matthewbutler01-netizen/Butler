package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueLineupConfigurationRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.domain.League;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueLineupEligibilityCoverageAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void reportsCompleteCoverageForExplicitStarterAndNonStartingSlotsInProviderOrder() throws Exception {
        Database database = database();
        League league = saveLeague(database);
        new LeagueLineupConfigurationRepository(database).replace(league.getId(),
            List.of("QB", "RB", "WR", "TE", "FLEX", "SUPER_FLEX", "BN", "IR", "TAXI"));

        var report = new LeagueLineupEligibilityCoverageAnalyzer(database).analyze(league.getId());

        assertEquals(LeagueLineupEligibilityCoverageAnalyzer.CoverageState.COMPLETE, report.state());
        assertTrue(report.legalLineupEligible());
        assertEquals(6, report.supportedStartingSlots());
        assertEquals(3, report.nonStartingSlots());
        assertEquals(0, report.unsupportedSlots());
        assertEquals("QB", report.slots().get(0).slot());
        assertEquals("SUPER_FLEX", report.slots().get(5).slot());
        assertEquals("BN", report.slots().get(6).slot());
        assertEquals(List.of("RB", "WR", "TE"), report.slots().get(4).eligibleFantasyPositions());
    }

    @Test
    void failsClosedWhenAnyProviderSlotIsUnsupported() throws Exception {
        Database database = database();
        League league = saveLeague(database);
        new LeagueLineupConfigurationRepository(database).replace(league.getId(),
            List.of("QB", "REC_FLEX", "BN"));

        var report = new LeagueLineupEligibilityCoverageAnalyzer(database).analyze(league.getId());

        assertEquals(LeagueLineupEligibilityCoverageAnalyzer.CoverageState.INCOMPLETE, report.state());
        assertFalse(report.legalLineupEligible());
        assertEquals(1, report.unsupportedSlots());
        assertEquals(LineupSlotEligibilityPolicy.SlotState.UNSUPPORTED, report.slots().get(1).state());
    }

    @Test
    void noConfigurationOrOnlyNonStartingSlotsCannotAuthorizeLineupSolving() throws Exception {
        Database database = database();
        League league = saveLeague(database);
        var analyzer = new LeagueLineupEligibilityCoverageAnalyzer(database);

        var missing = analyzer.analyze(league.getId());
        assertEquals(LeagueLineupEligibilityCoverageAnalyzer.CoverageState.NO_LINEUP_CONFIGURATION, missing.state());
        assertFalse(missing.legalLineupEligible());

        new LeagueLineupConfigurationRepository(database).replace(league.getId(), List.of("BN", "IR", "TAXI"));
        var noStarters = analyzer.analyze(league.getId());
        assertEquals(LeagueLineupEligibilityCoverageAnalyzer.CoverageState.NO_STARTING_SLOTS, noStarters.state());
        assertFalse(noStarters.legalLineupEligible());
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("lineup-coverage.db"));
        database.initialize();
        return database;
    }

    private static League saveLeague(Database database) throws Exception {
        League league = League.create("Test League");
        new LeagueRepository(database).save(league);
        return league;
    }
}
