package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ButlerLeaguePerformanceEvidenceCliTest {
    @Test
    void parsesDefaultAndExplicitSource() {
        var defaults = ButlerLeaguePerformanceEvidenceCli.parse(
            new String[]{"league", "performance-evidence", "l1", "2026"});
        assertEquals("l1", defaults.leagueId());
        assertEquals(2026, defaults.season());
        assertNull(defaults.source());

        var explicit = ButlerLeaguePerformanceEvidenceCli.parse(
            new String[]{"league", "performance-evidence", "l1", "2026", "sleeper"});
        assertEquals("sleeper", explicit.source());
    }

    @Test
    void rejectsMalformedSeasonOrArguments() {
        assertThrows(IllegalArgumentException.class, () -> ButlerLeaguePerformanceEvidenceCli.parse(
            new String[]{"league", "performance-evidence", "l1", "bad"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeaguePerformanceEvidenceCli.parse(
            new String[]{"league", "performance-evidence", "l1"}));
    }

    @Test
    void commandRouterRecognizesPerformanceEvidence() {
        assertEquals(ButlerCommandRouter.Route.LEAGUE_PERFORMANCE_EVIDENCE,
            ButlerCommandRouter.route(new String[]{"league", "performance-evidence", "l1", "2026"}));
    }
}
