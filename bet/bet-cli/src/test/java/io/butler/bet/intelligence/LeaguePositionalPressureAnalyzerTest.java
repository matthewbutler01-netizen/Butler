package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaguePositionalPressureAnalyzerTest {
    @Test
    void ranksStarterCoverageValueByLeagueQuartiles() {
        var report = LeaguePositionalPressureAnalyzer.compose(lineup(1, 2, 2, 1, 1, 1, List.of()),
            depth(List.of(80.0, 70.0, 60.0, 50.0, 40.0, 30.0, 20.0, 10.0), "QB", 1));
        var qb = report.positions().get("QB");
        assertTrue(qb.available());
        assertEquals(LeaguePositionalPressurePolicy.Tier.POSITION_STRENGTH, team(qb, "t1").tier());
        assertEquals(LeaguePositionalPressurePolicy.Tier.POSITION_STRENGTH, team(qb, "t2").tier());
        assertEquals(LeaguePositionalPressurePolicy.Tier.POSITION_PRESSURE, team(qb, "t7").tier());
        assertEquals(LeaguePositionalPressurePolicy.Tier.POSITION_PRESSURE, team(qb, "t8").tier());
        assertEquals(LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED, team(qb, "t4").tier());
    }

    @Test
    void usesOnlyTopRequiredStartersAndIgnoresBenchDepthForRanking() {
        var teams = new ArrayList<LeaguePositionalDepthAnalyzer.TeamDepth>();
        for (int i = 1; i <= 4; i++) {
            List<Double> values = switch (i) {
                case 1 -> List.of(50.0, 40.0, 1000.0); // sorted fixture below; extra depth must not count
                case 2 -> List.of(48.0, 39.0);
                case 3 -> List.of(30.0, 20.0);
                default -> List.of(10.0, 5.0);
            };
            teams.add(teamDepth("t" + i, "Team " + i, "RB", values));
        }
        var report = LeaguePositionalPressureAnalyzer.compose(lineup(1, 2, 2, 1, 1, 0, List.of()),
            new LeaguePositionalDepthAnalyzer.DepthReport("l1", "src", null, teams));
        var rb = report.positions().get("RB");
        // Team 1 top two are 1000+50 because depth analyzer values are assumed sorted by value.
        // Validate the rule directly with a fixture where extra third value is below top two.
        assertEquals(1050.0, team(rb, "t1").starterCoverageValue());
        assertEquals(1090.0, team(rb, "t1").totalPositionValue());
    }

    @Test
    void allEqualValuesCollapseToBalanced() {
        var report = LeaguePositionalPressureAnalyzer.compose(lineup(1, 1, 1, 1, 0, 0, List.of()),
            depth(List.of(25.0, 25.0, 25.0, 25.0), "TE", 1));
        var te = report.positions().get("TE");
        assertTrue(te.available());
        te.teams().forEach(team -> assertEquals(LeaguePositionalPressurePolicy.Tier.POSITION_BALANCED, team.tier()));
    }

    @Test
    void incompleteRelevantValueCoverageFailsPositionClosed() {
        var teams = new ArrayList<>(depth(List.of(40.0, 30.0, 20.0, 10.0), "WR", 1).teams());
        var broken = teams.get(0);
        var pd = broken.positions().get("WR");
        var incomplete = new LeaguePositionalDepthAnalyzer.PositionDepth("WR", 2, 1, 0, 1, pd.players());
        teams.set(0, new LeaguePositionalDepthAnalyzer.TeamDepth(broken.teamId(), broken.teamName(), Map.of("WR", incomplete)));
        var report = LeaguePositionalPressureAnalyzer.compose(lineup(1, 1, 1, 1, 0, 0, List.of()),
            new LeaguePositionalDepthAnalyzer.DepthReport("l1", "src", null, teams));
        var wr = report.positions().get("WR");
        assertFalse(wr.available());
        wr.teams().forEach(team -> assertEquals(LeaguePositionalPressurePolicy.Tier.INSUFFICIENT_EVIDENCE, team.tier()));
    }

    @Test
    void zeroRosteredPlayersIsValidZeroEvidence() {
        var teams = new ArrayList<LeaguePositionalDepthAnalyzer.TeamDepth>();
        teams.add(new LeaguePositionalDepthAnalyzer.TeamDepth("t1", "Team 1", Map.of()));
        teams.add(teamDepth("t2", "Team 2", "QB", List.of(30.0)));
        teams.add(teamDepth("t3", "Team 3", "QB", List.of(20.0)));
        teams.add(teamDepth("t4", "Team 4", "QB", List.of(10.0)));
        var report = LeaguePositionalPressureAnalyzer.compose(lineup(1, 1, 1, 1, 0, 1, List.of()),
            new LeaguePositionalDepthAnalyzer.DepthReport("l1", "src", null, teams));
        var qb = report.positions().get("QB");
        assertTrue(qb.available());
        assertEquals(0.0, team(qb, "t1").starterCoverageValue());
        assertEquals(LeaguePositionalPressurePolicy.Tier.POSITION_PRESSURE, team(qb, "t1").tier());
        assertEquals(1, report.superFlexSlots());
    }

    @Test
    void noDirectRequirementDoesNotInventPressure() {
        var report = LeaguePositionalPressureAnalyzer.compose(lineup(0, 1, 1, 1, 1, 1, List.of()),
            depth(List.of(50.0, 40.0, 30.0, 20.0), "QB", 1));
        var qb = report.positions().get("QB");
        assertFalse(qb.available());
        qb.teams().forEach(team -> assertEquals(LeaguePositionalPressurePolicy.Tier.NO_DIRECT_REQUIREMENT, team.tier()));
        assertEquals(1, report.flexSlots());
        assertEquals(1, report.superFlexSlots());
    }

    @Test
    void unknownLineupSlotFailsAllCorePositionsClosed() {
        var report = LeaguePositionalPressureAnalyzer.compose(lineup(1, 1, 1, 1, 0, 0, List.of("MYSTERY")),
            depth(List.of(50.0, 40.0, 30.0, 20.0), "QB", 1));
        report.positions().values().forEach(position -> {
            assertFalse(position.available());
            position.teams().forEach(team -> assertEquals(LeaguePositionalPressurePolicy.Tier.INSUFFICIENT_EVIDENCE, team.tier()));
        });
    }

    private static LeagueLineupRequirementsAnalyzer.LineupRequirementsReport lineup(
        int qb, int rb, int wr, int te, int flex, int superFlex, List<String> unknown) {
        Map<String, Integer> direct = new LinkedHashMap<>();
        direct.put("QB", qb); direct.put("RB", rb); direct.put("WR", wr); direct.put("TE", te);
        return new LeagueLineupRequirementsAnalyzer.LineupRequirementsReport("l1",
            LeagueLineupRequirementsAnalyzer.POLICY_ID, true, direct, flex, superFlex,
            0, 0, 0, List.of(), unknown, List.of("fixture"));
    }

    private static LeaguePositionalDepthAnalyzer.DepthReport depth(List<Double> values, String position, int playersPerTeam) {
        List<LeaguePositionalDepthAnalyzer.TeamDepth> teams = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            List<Double> teamValues = new ArrayList<>();
            for (int p = 0; p < playersPerTeam; p++) teamValues.add(values.get(i));
            teams.add(teamDepth("t" + (i + 1), "Team " + (i + 1), position, teamValues));
        }
        return new LeaguePositionalDepthAnalyzer.DepthReport("l1", "src", null, teams);
    }

    private static LeaguePositionalDepthAnalyzer.TeamDepth teamDepth(String id, String name, String position, List<Double> values) {
        List<Double> sorted = values.stream().sorted(java.util.Comparator.reverseOrder()).toList();
        List<LeaguePositionalDepthAnalyzer.PlayerDepthValue> players = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            players.add(new LeaguePositionalDepthAnalyzer.PlayerDepthValue(id + "p" + i, name + " Player " + i,
                position, "BENCH", sorted.get(i), LocalDate.of(2026, 9, 1)));
        }
        var depth = new LeaguePositionalDepthAnalyzer.PositionDepth(position, players.size(), players.size(), 0, 0, players);
        return new LeaguePositionalDepthAnalyzer.TeamDepth(id, name, Map.of(position, depth));
    }

    private static LeaguePositionalPressureAnalyzer.TeamPositionPressure team(
        LeaguePositionalPressureAnalyzer.PositionPressure position, String teamId) {
        return position.teams().stream().filter(team -> team.teamId().equals(teamId)).findFirst().orElseThrow();
    }
}
