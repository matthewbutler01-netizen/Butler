package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ButlerSleeperProviderNativeSeasonScoringAuditCliTest {
    @Test
    void requiresLeagueAndSeasonButDoesNotAllowTeamOrWeekSelection() {
        var options = ButlerSleeperProviderNativeSeasonScoringAuditCli.parse(
            new String[] {"league-1", "2025"});

        assertEquals("league-1", options.leagueId());
        assertEquals(2025, options.season());
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperProviderNativeSeasonScoringAuditCli.parse(
                new String[] {"league-1", "2025", "team-1"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperProviderNativeSeasonScoringAuditCli.parse(
                new String[] {"league-1", "2025", "team-1", "1"}));
    }
}
