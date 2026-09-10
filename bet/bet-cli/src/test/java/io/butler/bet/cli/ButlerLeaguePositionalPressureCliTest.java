package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeaguePositionalPressureCliTest {
    @Test
    void parsesLeagueOnly() {
        var options = ButlerLeaguePositionalPressureCli.parse(new String[]{"league", "positional-pressure", "l1"});
        assertEquals("l1", options.leagueId());
        assertNull(options.source());
        assertNull(options.minimumAsOf());
    }

    @Test
    void parsesSourceAndFreshness() {
        var options = ButlerLeaguePositionalPressureCli.parse(new String[]{
            "league", "positional-pressure", "l1", "dynastyprocess", "--minimum-as-of", "2026-09-01"});
        assertEquals("dynastyprocess", options.source());
        assertEquals(LocalDate.of(2026, 9, 1), options.minimumAsOf());
    }

    @Test
    void rejectsMalformedFreshnessFlag() {
        assertThrows(IllegalArgumentException.class, () -> ButlerLeaguePositionalPressureCli.parse(new String[]{
            "league", "positional-pressure", "l1", "--minimum-as-of"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeaguePositionalPressureCli.parse(new String[]{
            "league", "positional-pressure", "l1", "dynastyprocess", "--minimum-as-of", "bad-date"}));
    }

    @Test
    void recognizesAndRoutesCommand() {
        String[] args = {"league", "positional-pressure", "l1"};
        assertTrue(ButlerLeaguePositionalPressureCli.isCommand(args));
        assertEquals(ButlerCommandRouter.Route.LEAGUE_POSITIONAL_PRESSURE, ButlerCommandRouter.route(args));
    }

    @Test
    void formatsCompactTeamPressureRows() {
        String rendered = ButlerLeaguePositionalPressureCli.formatTeam(
            "Alpha", "team-7", "CONTENDER", 1234.5, 2345.67, 8, 7, 1, 0);

        String lineSeparator = System.lineSeparator();
        assertEquals(
            "  Alpha: tier=CONTENDER starter-coverage-value=1234.50 total-position-value=2345.67" + lineSeparator
                + "    players=8 valued=7 stale=1 missing=0 team-id=team-7" + lineSeparator,
            rendered);
    }
}
