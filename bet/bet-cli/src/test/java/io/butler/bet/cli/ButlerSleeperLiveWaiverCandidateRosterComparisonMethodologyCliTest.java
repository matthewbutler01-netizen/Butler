package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperLiveWaiverCandidateRosterComparisonMethodology;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperLiveWaiverCandidateRosterComparisonMethodologyCliTest {
    @Test
    void rendersFrozenMethodologyWithoutSelectionSemantics() {
        var missing = new SleeperLiveWaiverCandidateRosterComparisonMethodology.ProtectedMissingProduction(
            "13288", "Nicholas Singleton", "RB", "BENCH");
        var rule = new SleeperLiveWaiverCandidateRosterComparisonMethodology.SupportedScoringRule(
            "rec", "receptions", 1.0d, 1);
        var report = new SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyReport(
            SleeperLiveWaiverCandidateRosterComparisonMethodology.POLICY_ID,
            "L", "owner", "M", "W", "S", 2026, "in_season", 1, 1,
            51, 42, 32, 10,
            15, 9, 5, 1, 0,
            13, 2, List.of(missing),
            Map.of("rec", 1.0d), Map.of("rec", rule), Map.of("bonus", 0.5d),
            List.of("rec"), List.of("BENCH", "RESERVE"),
            "EXACT_POSITION_ONLY",
            "COMMON_2025_SOURCE_ONLY_ALL_COMMON_SOURCES_MUST_AGREE",
            "SUPPORTED_LEAGUE_SCORING_SUBTOTAL_PER_GAME_NOT_FULL_FANTASY_POINTS",
            "NEWCOMER_REVIEW_NONNUMERIC",
            "MISSING_TARGET_PRIOR_PRODUCTION_PROTECTED_UNRESOLVED",
            "MARKET_TEAM_STATUS_INJURY_DEPTH_DESCRIPTIVE_ONLY_NOT_ARITHMETIC",
            SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyState.METHODOLOGY_FROZEN_NO_SELECTION);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerSleeperLiveWaiverCandidateRosterComparisonMethodologyCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains("Comparison methodology state: METHODOLOGY_FROZEN_NO_SELECTION"));
        assertTrue(output.contains("Replacement comparator slots: [BENCH, RESERVE]"));
        assertTrue(output.contains("Unsupported/excluded league scoring settings"));
        assertTrue(output.contains("does not rank waiver candidates"));
        assertTrue(output.contains("supported scoring subtotal is not full fantasy points"));
        assertFalse(output.contains("Recommended add:"));
        assertFalse(output.contains("Recommended drop:"));
        assertFalse(output.contains("FAAB bid:"));
    }
}
