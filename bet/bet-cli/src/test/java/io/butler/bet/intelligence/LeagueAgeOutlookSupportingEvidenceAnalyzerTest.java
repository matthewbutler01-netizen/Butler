package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LeagueAgeOutlookSupportingEvidenceAnalyzerTest {
    @Test
    void preservesGovernedMetadataWithoutCreatingDecisionWeight() {
        var report = new LeagueAgeOutlookEvidenceAnalyzer.LeagueAgeOutlookReport(
            "league-1", 2026, LocalDate.of(2026, 9, 1), "sleeper",
            AgingModelSupportPolicy.POLICY_ID, AgingModelAgeOutlookPolicy.POLICY_ID,
            AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS,
            "nflverse-players", "nflverse", 411, 411, List.of());

        var result = LeagueAgeOutlookSupportingEvidenceAnalyzer.adapt(report);

        assertEquals("league-1", result.leagueId());
        assertEquals(AgingModelAgeOutlookPolicy.POLICY_ID, result.outlookPolicyId());
        assertEquals(0, result.totalFlags());
        assertEquals(0, result.directionalFlags());
    }

    @Test
    void supportingFlagRequiresCompleteIdentityAndProvenance() {
        assertThrows(IllegalArgumentException.class, () -> new DecisionSupportingEvidenceFlag(
            "", "AGE_OUTLOOK", "RECEIVING_YARDS_PER_GAME",
            DecisionSupportingEvidenceFlag.Signal.INCONCLUSIVE,
            "Historical aging evidence is inconclusive.",
            AgingModelAgeOutlookPolicy.POLICY_ID,
            "nflverse"));
    }
}
