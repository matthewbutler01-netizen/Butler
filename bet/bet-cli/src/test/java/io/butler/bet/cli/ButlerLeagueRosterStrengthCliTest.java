package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ButlerLeagueRosterStrengthCliTest {
    @Test
    void parsesDefaultSourceExplicitSourceAndMinimumAsOf() {
        var defaults = ButlerLeagueRosterStrengthCli.parse(new String[]{"league", "roster-strength", "l1"});
        assertEquals("l1", defaults.leagueId());
        assertNull(defaults.source());
        assertNull(defaults.minimumAsOf());

        var explicit = ButlerLeagueRosterStrengthCli.parse(new String[]{"league", "roster-strength", "l1", "market"});
        assertEquals("market", explicit.source());

        var dated = ButlerLeagueRosterStrengthCli.parse(
            new String[]{"league", "roster-strength", "l1", "market", "--minimum-as-of", "2026-09-01"});
        assertEquals(LocalDate.of(2026, 9, 1), dated.minimumAsOf());
    }

    @Test
    void rejectsMalformedArguments() {
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueRosterStrengthCli.parse(
            new String[]{"league", "roster-strength"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueRosterStrengthCli.parse(
            new String[]{"league", "roster-strength", "l1", "--minimum-as-of", "bad"}));
    }

    @Test
    void routerRecognizesRosterStrength() {
        assertEquals(ButlerCommandRouter.Route.LEAGUE_ROSTER_STRENGTH,
            ButlerCommandRouter.route(new String[]{"league", "roster-strength", "l1"}));
    }
}
