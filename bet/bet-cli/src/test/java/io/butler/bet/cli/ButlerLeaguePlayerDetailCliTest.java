package io.butler.bet.cli;

import io.butler.bet.intelligence.LeagueAgeContextAnalyzer;
import io.butler.bet.intelligence.LeagueAgeProductionContextAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeaguePlayerDetailCliTest {

    @Test
    void parsesExactPlayerAndOptionalSeason() {
        var implicit = ButlerLeaguePlayerDetailCli.parse(
            new String[]{"league", "player-detail", "l1", "p1"});
        assertEquals("l1", implicit.leagueId());
        assertEquals("p1", implicit.playerId());
        assertNull(implicit.season());

        var explicit = ButlerLeaguePlayerDetailCli.parse(
            new String[]{"league", "player-detail", "l1", "p1", "2026"});
        assertEquals(2026, explicit.season());
    }

    @Test
    void rejectsMissingExtraAndMalformedCoordinates() {
        assertThrows(IllegalArgumentException.class, () ->
            ButlerLeaguePlayerDetailCli.parse(new String[]{"league", "player-detail", "l1"}));
        assertThrows(IllegalArgumentException.class, () ->
            ButlerLeaguePlayerDetailCli.parse(new String[]{"league", "player-detail", "l1", "p1", "2026", "extra"}));
        assertThrows(IllegalArgumentException.class, () ->
            ButlerLeaguePlayerDetailCli.parse(new String[]{"league", "player-detail", "l1", "p1", "bad"}));
    }

    @Test
    void missingProductionStaysMissingRatherThanBecomingZero() {
        var player = new LeagueAgeProductionContextAnalyzer.PlayerAgeProductionContext(
            "p1", "Player One", "WR", "BENCH", 25,
            LeagueAgeContextAnalyzer.AgeProvenance.EXACT_BIRTH_DATE,
            false, 0, null, null, null, null, null, null, null, null, null);
        String output = print(report(player));

        assertTrue(output.contains("Production snapshot: MISSING"));
        assertTrue(output.contains("Games played: UNAVAILABLE"));
        assertTrue(output.contains("Receiving yards/game: UNAVAILABLE"));
    }

    @Test
    void zeroGameSnapshotIsDistinctFromMissingProduction() {
        var player = new LeagueAgeProductionContextAnalyzer.PlayerAgeProductionContext(
            "p1", "Player One", "WR", "BENCH", 25,
            LeagueAgeContextAnalyzer.AgeProvenance.EXACT_BIRTH_DATE,
            true, 0, null, null, null, null, null, null, null, null, null);
        String output = print(report(player));

        assertTrue(output.contains("Production snapshot: AVAILABLE"));
        assertTrue(output.contains("Games played: 0"));
        assertTrue(output.contains("Receiving yards/game: UNAVAILABLE"));
    }

    private static ButlerLeaguePlayerDetailCli.PlayerDetailReport report(
        LeagueAgeProductionContextAnalyzer.PlayerAgeProductionContext player) {
        return new ButlerLeaguePlayerDetailCli.PlayerDetailReport(
            "l1", 2026, LocalDate.of(2026, 9, 19), "profile-source", null,
            "production-source", "t1", "Team One", player,
            LocalDate.of(2026, 9, 1), "support-policy", "outlook-policy",
            "model-profile-source", "model-production-source", List.of());
    }

    private static String print(ButlerLeaguePlayerDetailCli.PlayerDetailReport report) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            ButlerLeaguePlayerDetailCli.print(report);
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
