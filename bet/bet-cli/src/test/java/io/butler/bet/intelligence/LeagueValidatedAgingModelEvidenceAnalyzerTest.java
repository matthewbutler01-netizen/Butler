package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueValidatedAgingModelEvidenceAnalyzerTest {
    @Test
    void composesOnlyMatchingPolicyAndSources() {
        var league = new LeagueAgingModelEvidenceAnalyzer.LeagueAgingModelEvidenceReport(
            "league-1", 2026, LocalDate.of(2026, 9, 1), "sleeper",
            AgingModelSupportPolicy.POLICY_ID,
            AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS,
            "nflverse-players", "nflverse", List.of());
        var validation = new AgingModelPublicationValidationAnalyzer.ValidationReport(
            "nflverse-players", "nflverse", AgingModelSupportPolicy.POLICY_ID,
            AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS,
            411, 411, List.of());

        var result = LeagueValidatedAgingModelEvidenceAnalyzer.compose(league, validation);

        assertEquals("league-1", result.leagueId());
        assertEquals(2026, result.season());
        assertEquals(411, result.publishedModelCells());
        assertTrue(result.allPublishedModelCellsValidationComplete());
        assertEquals(0, result.totalPlayers());
    }

    @Test
    void rejectsPolicyOrSourceDrift() {
        var league = new LeagueAgingModelEvidenceAnalyzer.LeagueAgingModelEvidenceReport(
            "league-1", 2026, LocalDate.of(2026, 9, 1), "sleeper",
            AgingModelSupportPolicy.POLICY_ID,
            AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS,
            "nflverse-players", "nflverse", List.of());
        var wrongPolicy = new AgingModelPublicationValidationAnalyzer.ValidationReport(
            "nflverse-players", "nflverse", "different-policy",
            AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS,
            1, 1, List.of());
        var wrongSource = new AgingModelPublicationValidationAnalyzer.ValidationReport(
            "other-source", "nflverse", AgingModelSupportPolicy.POLICY_ID,
            AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS,
            1, 1, List.of());

        assertThrows(IllegalStateException.class,
            () -> LeagueValidatedAgingModelEvidenceAnalyzer.compose(league, wrongPolicy));
        assertThrows(IllegalStateException.class,
            () -> LeagueValidatedAgingModelEvidenceAnalyzer.compose(league, wrongSource));
    }
}
