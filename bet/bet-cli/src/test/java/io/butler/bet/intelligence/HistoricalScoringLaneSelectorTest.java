package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.ProviderPlayerWeekPointsEvidenceRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.ProviderPlayerWeekPointsEvidence;
import io.butler.bet.domain.Team;
import io.butler.bet.domain.TeamWeekRosterEvidence;
import io.butler.bet.sleeper.SleeperProviderNativeSeasonScoringAudit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoricalScoringLaneSelectorTest {
    @TempDir Path tempDir;

    @Test
    void mixedProviderProvenanceBlocksProviderLaneAndNeverFallsBackToNflverse() throws Exception {
        Database database = new Database(tempDir.resolve("lane-selector.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "Team One"));
        new TeamWeekRosterEvidenceRepository(database).save(TeamWeekRosterEvidence.create(
            "l1", "t1", 2026, 3, List.of("s1", "s2"), List.of(), "sleeper", AS_OF));

        List<ProviderPlayerWeekPointsEvidence> rows = List.of(
            ProviderPlayerWeekPointsEvidence.create(
                "l1", "t1", "1", "provider-l1", 2026, 3, "s1",
                new BigDecimal("12.0"), "sleeper",
                SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE, AS_OF),
            ProviderPlayerWeekPointsEvidence.create(
                "l1", "t1", "1", "provider-l1", 2026, 3, "s2",
                new BigDecimal("8.0"), "sleeper", "unexpected.surface", AS_OF));
        new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
            "l1", 2026, "sleeper", AS_OF, rows);

        var selection = new HistoricalScoringLaneSelector(database).select("l1", 2026);

        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, selection.lane());
        assertEquals(HistoricalScoringLaneSelector.SelectionState.BLOCKED, selection.state());
        assertFalse(selection.ready());
        assertTrue(selection.blockers().stream().anyMatch(value -> value.contains("mixed source surfaces")));
        assertTrue(selection.blockers().stream().anyMatch(value -> value.contains("unexpected source surface")));
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 6);
}
