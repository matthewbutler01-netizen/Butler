package io.butler.bet.cli;

import io.butler.bet.intelligence.LeagueAssetConcentrationAnalyzer;
import io.butler.bet.intelligence.LeagueCompositeTeamProfileAnalyzer;
import io.butler.bet.intelligence.LeagueDraftCapitalTimelineAnalyzer;
import io.butler.bet.intelligence.LeaguePositionalDepthAnalyzer;
import io.butler.bet.intelligence.LeagueRosterSlotValueAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueFranchiseDetailCliTest {

    @Test
    void parsesExactLeagueAndTeam() {
        var options = ButlerLeagueFranchiseDetailCli.parse(
            new String[]{"league", "franchise-detail", "l1", "t1"});
        assertEquals("l1", options.leagueId());
        assertEquals("t1", options.teamId());
    }

    @Test
    void rejectsMissingAndExtraCoordinates() {
        assertThrows(IllegalArgumentException.class, () ->
            ButlerLeagueFranchiseDetailCli.parse(new String[]{"league", "franchise-detail", "l1"}));
        assertThrows(IllegalArgumentException.class, () ->
            ButlerLeagueFranchiseDetailCli.parse(
                new String[]{"league", "franchise-detail", "l1", "t1", "extra"}));
    }

    @Test
    void selectsExactlyOneFranchise() {
        var report = new LeagueCompositeTeamProfileAnalyzer.CompositeProfileReport(
            "l1", "source", null, List.of(team("t1", "Team One"), team("t2", "Team Two")));

        var selected = ButlerLeagueFranchiseDetailCli.select("t2", report);
        assertEquals("t2", selected.team().teamId());
        assertEquals("Team Two", selected.team().teamName());

        assertThrows(IllegalArgumentException.class, () ->
            ButlerLeagueFranchiseDetailCli.select("missing", report));
    }

    @Test
    void printsCoverageAndNeutralBoundaryWithoutInventingCertainty() {
        var report = new ButlerLeagueFranchiseDetailCli.FranchiseDetailReport(
            "l1", "source", null, team("t1", "Team One"));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            ButlerLeagueFranchiseDetailCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Team name: Team One"));
        assertTrue(output.contains("Asset coverage: valued=1 total=2 stale=1 missing=1"));
        assertTrue(output.contains("Roster coverage: valued=1 total=2 stale=0 missing=1"));
        assertTrue(output.contains("Draft coverage: valued=1 total=2 stale=1 missing=1"));
        assertTrue(output.contains("Position: WR | players=2 | valued=1 | stale=1 | missing=1"));
        assertTrue(output.contains("no new ranking"));
        assertTrue(output.contains("no new ranking, contender/rebuilder label"));
    }

    private static LeagueCompositeTeamProfileAnalyzer.TeamProfile team(String teamId, String teamName) {
        var concentration = new LeagueAssetConcentrationAnalyzer.TeamConcentration(
            teamId, teamName, "source", null, 150.0, 2, 1, 1, 1, List.of());
        var roster = new LeagueRosterSlotValueAnalyzer.TeamRosterSlotContext(
            teamId, teamName, Map.of(
                "STARTER", new LeagueRosterSlotValueAnalyzer.SlotValue("STARTER", 90.0, 1, 0, 0, 1),
                "BENCH", new LeagueRosterSlotValueAnalyzer.SlotValue("BENCH", 0.0, 0, 0, 1, 1)));
        var depth = new LeaguePositionalDepthAnalyzer.TeamDepth(
            teamId, teamName, Map.of(
                "WR", new LeaguePositionalDepthAnalyzer.PositionDepth(
                    "WR", 2, 1, 1, 1, List.of(
                        new LeaguePositionalDepthAnalyzer.PlayerDepthValue(
                            "p1", "Player One", "WR", "STARTER", 90.0,
                            java.time.LocalDate.of(2026, 9, 1))))));
        var draft = new LeagueDraftCapitalTimelineAnalyzer.TeamDraftCapital(
            teamId, teamName, 60.0, 1, 1, 1, 2, List.of());

        return new LeagueCompositeTeamProfileAnalyzer.TeamProfile(
            teamId, teamName, concentration, roster, depth, draft);
    }
}
