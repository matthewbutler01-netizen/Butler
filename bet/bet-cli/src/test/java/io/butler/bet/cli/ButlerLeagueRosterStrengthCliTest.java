package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void rendersCompactPrimaryRowWithIndentedEvidenceDetail() {
        String rendered = ButlerLeagueRosterStrengthCli.formatTeam(
            "Butler Brigade", "team-12", "STRONG", 12345.67, 23456.78,
            24, 26, 92.3, 1, 2);

        String lineSeparator = System.lineSeparator();
        assertEquals(
            "Butler Brigade: tier=STRONG starter-value=12345.67 total-player-value=23456.78" + lineSeparator
                + "  coverage=24/26 (92.3%) stale=1 missing=2 team-id=team-12" + lineSeparator,
            rendered);

        String[] lines = rendered.split("\\R");
        assertEquals(2, lines.length);
        assertTrue(lines[0].length() <= 120, "primary row should remain compact in a typical terminal");
        assertTrue(lines[1].startsWith("  coverage="), "evidence detail should be indented beneath the primary row");
    }
}
