package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueTeamPostureAnalyzerTest {
    @Test
    void composesAgreementMatrixWithoutWeighting() {
        var result = LeagueTeamPostureAnalyzer.compose(
            competitive(true,
                teamCompetitive("a", "Alpha", LeagueCompetitiveTierPolicy.Tier.FRONT_TIER),
                teamCompetitive("b", "Beta", LeagueCompetitiveTierPolicy.Tier.BACK_TIER),
                teamCompetitive("c", "Gamma", LeagueCompetitiveTierPolicy.Tier.FRONT_TIER),
                teamCompetitive("d", "Delta", LeagueCompetitiveTierPolicy.Tier.MIDDLE_TIER)),
            roster(true,
                teamRoster("a", "Alpha", LeagueRosterStrengthTierPolicy.Tier.FRONT_ROSTER_TIER),
                teamRoster("b", "Beta", LeagueRosterStrengthTierPolicy.Tier.BACK_ROSTER_TIER),
                teamRoster("c", "Gamma", LeagueRosterStrengthTierPolicy.Tier.BACK_ROSTER_TIER),
                teamRoster("d", "Delta", LeagueRosterStrengthTierPolicy.Tier.FRONT_ROSTER_TIER)));

        assertTrue(result.available());
        assertEquals(TeamPosturePolicy.Posture.CONTENDER, posture(result, "a"));
        assertEquals(TeamPosturePolicy.Posture.REBUILDER, posture(result, "b"));
        assertEquals(TeamPosturePolicy.Posture.MIDDLE_OR_MIXED, posture(result, "c"));
        assertEquals(TeamPosturePolicy.Posture.MIDDLE_OR_MIXED, posture(result, "d"));
    }

    @Test
    void unavailableDimensionKeepsPostureInsufficient() {
        var result = LeagueTeamPostureAnalyzer.compose(
            competitive(false, teamCompetitive("a", "Alpha", LeagueCompetitiveTierPolicy.Tier.INSUFFICIENT_EVIDENCE)),
            roster(true, teamRoster("a", "Alpha", LeagueRosterStrengthTierPolicy.Tier.FRONT_ROSTER_TIER)));
        assertFalse(result.available());
        assertEquals(TeamPosturePolicy.Posture.INSUFFICIENT_EVIDENCE, posture(result, "a"));
    }

    @Test
    void rejectsLeagueTeamSetAndTeamNameDrift() {
        var roster = roster(true, teamRoster("a", "Alpha", LeagueRosterStrengthTierPolicy.Tier.FRONT_ROSTER_TIER));
        assertThrows(IllegalStateException.class, () -> LeagueTeamPostureAnalyzer.compose(
            new LeagueCompetitiveTierAnalyzer.CompetitiveTierReport("other", 2026, "sleeper",
                LeagueCompetitiveTierPolicy.POLICY_ID, true, null,
                List.of(teamCompetitive("a", "Alpha", LeagueCompetitiveTierPolicy.Tier.FRONT_TIER))), roster));
        assertThrows(IllegalStateException.class, () -> LeagueTeamPostureAnalyzer.compose(
            competitive(true, teamCompetitive("a", "Alpha", LeagueCompetitiveTierPolicy.Tier.FRONT_TIER),
                teamCompetitive("b", "Beta", LeagueCompetitiveTierPolicy.Tier.MIDDLE_TIER)), roster));
        assertThrows(IllegalStateException.class, () -> LeagueTeamPostureAnalyzer.compose(
            competitive(true, teamCompetitive("a", "Wrong", LeagueCompetitiveTierPolicy.Tier.FRONT_TIER)), roster));
    }

    private static TeamPosturePolicy.Posture posture(LeagueTeamPostureAnalyzer.PostureReport report, String teamId) {
        return report.teams().stream().filter(team -> team.teamId().equals(teamId)).findFirst().orElseThrow().posture();
    }

    private static LeagueCompetitiveTierAnalyzer.CompetitiveTierReport competitive(
        boolean available, LeagueCompetitiveTierAnalyzer.TeamTier... teams) {
        return new LeagueCompetitiveTierAnalyzer.CompetitiveTierReport("l1", 2026, "sleeper",
            LeagueCompetitiveTierPolicy.POLICY_ID, available, available ? null : "insufficient", List.of(teams));
    }

    private static LeagueCompetitiveTierAnalyzer.TeamTier teamCompetitive(
        String id, String name, LeagueCompetitiveTierPolicy.Tier tier) {
        return new LeagueCompetitiveTierAnalyzer.TeamTier(id, name, tier, 8, .5, 100.0, 0.0);
    }

    private static LeagueRosterStrengthTierAnalyzer.RosterStrengthReport roster(
        boolean available, LeagueRosterStrengthTierAnalyzer.TeamRosterStrength... teams) {
        return new LeagueRosterStrengthTierAnalyzer.RosterStrengthReport("l1", "dynastyprocess", null,
            LeagueRosterStrengthTierPolicy.POLICY_ID, available, available ? null : "insufficient", List.of(teams));
    }

    private static LeagueRosterStrengthTierAnalyzer.TeamRosterStrength teamRoster(
        String id, String name, LeagueRosterStrengthTierPolicy.Tier tier) {
        return new LeagueRosterStrengthTierAnalyzer.TeamRosterStrength(id, name, 100.0, 200.0, 2, 2, 0, 0, tier);
    }
}
