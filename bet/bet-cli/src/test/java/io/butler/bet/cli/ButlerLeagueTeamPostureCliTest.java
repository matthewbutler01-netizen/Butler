package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ButlerLeagueTeamPostureCliTest {
    @Test
    void parsesDefaultAndExplicitRosterValueSource() {
        var defaults = ButlerLeagueTeamPostureCli.parse(new String[]{"league", "team-posture", "l1", "2026"});
        assertEquals("l1", defaults.leagueId());
        assertEquals(2026, defaults.season());
        assertNull(defaults.rosterValueSource());

        var explicit = ButlerLeagueTeamPostureCli.parse(
            new String[]{"league", "team-posture", "l1", "2026", "dynastyprocess"});
        assertEquals("dynastyprocess", explicit.rosterValueSource());
    }

    @Test
    void rejectsMalformedArgumentsAndRoutesCommand() {
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueTeamPostureCli.parse(
            new String[]{"league", "team-posture", "l1", "bad"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueTeamPostureCli.parse(
            new String[]{"league", "team-posture", "l1"}));
        assertEquals(ButlerCommandRouter.Route.LEAGUE_TEAM_POSTURE,
            ButlerCommandRouter.route(new String[]{"league", "team-posture", "l1", "2026"}));
    }
}
