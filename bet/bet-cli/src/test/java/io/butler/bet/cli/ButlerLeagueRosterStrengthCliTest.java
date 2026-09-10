package io.butler.bet.cli;

import io.butler.bet.intelligence.LeagueRosterStrengthTierAnalyzer;
import io.butler.bet.intelligence.LeagueRosterStrengthTierPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueRosterStrengthCliTest {
    @Test
    void rendersRosterStrengthAsCompactPrimaryAndDetailRows() {
        var team = new LeagueRosterStrengthTierAnalyzer.TeamRosterStrength(
            "team-123", "Love: JT, Jeanty &Javonte", 850.25, 1425.50,
            25, 24, 1, 0, LeagueRosterStrengthTierPolicy.Tier.FRONT_ROSTER_TIER);

        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            ButlerLeagueRosterStrengthCli.printTeam(team);
        } finally {
            System.setOut(original);
        }
        String output = buffer.toString(StandardCharsets.UTF_8);
        String[] lines = output.strip().split("\\R");

        assertEquals(2, lines.length);
        assertEquals(
            "Love: JT, Jeanty &Javonte  tier=FRONT_ROSTER_TIER  starter-value=850.25  total-player-value=1425.50",
            lines[0]);
        assertEquals("  coverage=24/25 (96.0%)  stale=1  missing=0  team-id=team-123", lines[1]);
        assertTrue(lines[0].length() <= 120);
    }

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
