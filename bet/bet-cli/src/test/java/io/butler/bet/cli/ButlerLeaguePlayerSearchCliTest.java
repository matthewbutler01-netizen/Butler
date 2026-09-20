package io.butler.bet.cli;

import io.butler.bet.intelligence.LeagueAssetSearchAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeaguePlayerSearchCliTest {

    @Test
    void parsesMultiWordQueryFromTrailingArguments() {
        var options = ButlerLeaguePlayerSearchCli.parse(
            new String[]{"league", "player-search", "l1", "Amon-Ra", "St.", "Brown"});

        assertEquals("l1", options.leagueId());
        assertEquals("Amon-Ra St. Brown", options.query());
    }

    @Test
    void rejectsMissingQuery() {
        assertThrows(IllegalArgumentException.class, () ->
            ButlerLeaguePlayerSearchCli.parse(new String[]{"league", "player-search", "l1"}));
    }

    @Test
    void printsPlayersInExistingAnalyzerOrderWithoutRankingThem() {
        var report = new LeagueAssetSearchAnalyzer.SearchReport(
            "l1",
            "source",
            "Brown",
            List.of(
                new LeagueAssetSearchAnalyzer.PlayerMatch(
                    "t2", "Beta", "p2", "Second Brown", "WR", "DET", "BENCH",
                    null, null),
                new LeagueAssetSearchAnalyzer.PlayerMatch(
                    "t1", "Alpha", "p1", "First Brown", "WR", "PHI", "STARTER",
                    123.45, LocalDate.of(2026, 9, 19))),
            List.of(new LeagueAssetSearchAnalyzer.DraftPickMatch(
                "t1", "Alpha", "pick1", 2027, 1, "2027 1st",
                "t1", "Alpha", null, 55.0, LocalDate.of(2026, 9, 1))));

        String output = capture(report);

        assertTrue(output.indexOf("Player ID: p2") < output.indexOf("Player ID: p1"));
        assertTrue(output.contains("Value: UNAVAILABLE"));
        assertTrue(output.contains("Value: 123.45"));
        assertTrue(output.contains("Other asset matches: 1"));
        assertTrue(output.contains("draft-pick matches are counted but not shown here"));
        assertTrue(output.contains("NOT A RANKING"));
    }

    @Test
    void outputCarriesExactIdsNeededForPlayerDetailLinks() {
        var report = new LeagueAssetSearchAnalyzer.SearchReport(
            "league-id", "source", "Jeanty",
            List.of(new LeagueAssetSearchAnalyzer.PlayerMatch(
                "team-id", "Love: JT, Jeanty &Javonte", "player-id",
                "Ashton Jeanty", "RB", "LV", "STARTER",
                100.0, LocalDate.of(2026, 9, 19))),
            List.of());

        String output = capture(report);

        assertTrue(output.contains("League ID: league-id"));
        assertTrue(output.contains("Player ID: player-id"));
        assertTrue(output.contains("Owner team ID: team-id"));
        assertTrue(output.contains("Owner team name: Love: JT, Jeanty &Javonte"));
    }

    private static String capture(LeagueAssetSearchAnalyzer.SearchReport report) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            ButlerLeaguePlayerSearchCli.print(report);
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
