package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueRosterStrengthTierAnalyzerBf718Test {

    @Test
    void normalAnalyzerPathUsesOnlyRosterSlotEvidence() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/LeagueRosterStrengthTierAnalyzer.java");

        assertTrue(source.contains("private final LeagueRosterSlotValueAnalyzer rosterSlots;"));
        assertTrue(source.contains("this.rosterSlots = new LeagueRosterSlotValueAnalyzer("));
        assertEquals(4, occurrences(source, "return compose(rosterSlots.analyze("));

        assertFalse(source.contains("private final LeagueCompositeTeamProfileAnalyzer profiles;"));
        assertFalse(source.contains("compose(profiles.analyze("));
        assertFalse(source.contains("new LeagueCompositeTeamProfileAnalyzer("));
        assertFalse(source.contains("new LeaguePositionalDepthAnalyzer("));
        assertFalse(source.contains("new LeagueDraftCapitalTimelineAnalyzer("));
        assertFalse(source.contains("new LeagueAssetConcentrationAnalyzer("));

        assertTrue(source.contains("public static RosterStrengthReport compose(LeagueCompositeTeamProfileAnalyzer.CompositeProfileReport profileReport)"),
            "BF-718 must preserve compatibility composition for callers already holding a composite profile");
        assertTrue(source.contains("public static RosterStrengthReport compose(LeagueRosterSlotValueAnalyzer.RosterSlotReport rosterReport)"));
    }

    @Test
    void directRosterSlotCompositionPreservesStarterTotalAndCoverageEvidence() {
        var report = new LeagueRosterSlotValueAnalyzer.RosterSlotReport(
            "league-1", "market", null, Map.of(), List.of(
                team("a", "Alpha", 100.0, 50.0),
                team("b", "Beta", 90.0, 40.0),
                team("c", "Charlie", 80.0, 30.0),
                team("d", "Delta", 70.0, 20.0)));

        var strength = LeagueRosterStrengthTierAnalyzer.compose(report);

        assertTrue(strength.available());
        assertEquals("league-1", strength.leagueId());
        assertEquals("market", strength.source());
        assertEquals(4, strength.teams().size());

        var alpha = strength.teams().stream().filter(team -> team.teamId().equals("a")).findFirst().orElseThrow();
        assertEquals(100.0, alpha.starterValue());
        assertEquals(150.0, alpha.totalPlayerValue());
        assertEquals(2, alpha.totalPlayers());
        assertEquals(2, alpha.valuedPlayers());
        assertEquals(0, alpha.stalePlayers());
        assertEquals(0, alpha.missingPlayers());
        assertEquals(LeagueRosterStrengthTierPolicy.Tier.FRONT_ROSTER_TIER, alpha.tier());

        var delta = strength.teams().stream().filter(team -> team.teamId().equals("d")).findFirst().orElseThrow();
        assertEquals(LeagueRosterStrengthTierPolicy.Tier.BACK_ROSTER_TIER, delta.tier());
    }

    private static LeagueRosterSlotValueAnalyzer.TeamRosterSlotContext team(
        String id, String name, double starter, double bench) {
        return new LeagueRosterSlotValueAnalyzer.TeamRosterSlotContext(id, name, Map.of(
            "STARTER", new LeagueRosterSlotValueAnalyzer.SlotValue("STARTER", starter, 1, 0, 0, 1),
            "BENCH", new LeagueRosterSlotValueAnalyzer.SlotValue("BENCH", bench, 1, 0, 0, 1)));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) count++;
        return count;
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IOException("BF-718 test could not locate " + relativePath);
    }
}
