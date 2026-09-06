package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeaguePerformanceEvidenceCliTest {
    @Test
    void parsesDefaultExplicitSourceAndSleeperSyncFlag() {
        var defaults = ButlerLeaguePerformanceEvidenceCli.parse(
            new String[]{"league", "performance-evidence", "l1", "2026"});
        assertEquals("l1", defaults.leagueId());
        assertEquals(2026, defaults.season());
        assertNull(defaults.source());
        assertFalse(defaults.syncSleeper());

        var explicit = ButlerLeaguePerformanceEvidenceCli.parse(
            new String[]{"league", "performance-evidence", "l1", "2026", "sleeper"});
        assertEquals("sleeper", explicit.source());
        assertFalse(explicit.syncSleeper());

        var sync = ButlerLeaguePerformanceEvidenceCli.parse(
            new String[]{"league", "performance-evidence", "l1", "2025", "--SYNC-SLEEPER"});
        assertEquals(2025, sync.season());
        assertNull(sync.source());
        assertTrue(sync.syncSleeper());
    }

    @Test
    void rejectsMalformedSeasonOrArguments() {
        assertThrows(IllegalArgumentException.class, () -> ButlerLeaguePerformanceEvidenceCli.parse(
            new String[]{"league", "performance-evidence", "l1", "bad"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeaguePerformanceEvidenceCli.parse(
            new String[]{"league", "performance-evidence", "l1"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeaguePerformanceEvidenceCli.parse(
            new String[]{"league", "performance-evidence", "l1", "2025", "sleeper", "--sync-sleeper"}));
    }

    @Test
    void commandRouterRecognizesPerformanceEvidenceIncludingSyncFlag() {
        assertEquals(ButlerCommandRouter.Route.LEAGUE_PERFORMANCE_EVIDENCE,
            ButlerCommandRouter.route(new String[]{"league", "performance-evidence", "l1", "2026"}));
        assertEquals(ButlerCommandRouter.Route.LEAGUE_PERFORMANCE_EVIDENCE,
            ButlerCommandRouter.route(new String[]{"league", "performance-evidence", "l1", "2025", "--sync-sleeper"}));
    }
}
