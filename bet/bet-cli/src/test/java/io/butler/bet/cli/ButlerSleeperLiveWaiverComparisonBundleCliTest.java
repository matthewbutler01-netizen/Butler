package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperLiveWaiverCandidateRosterComparisonMethodology;
import io.butler.bet.sleeper.SleeperLiveWaiverComparisonExecutionBundle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperLiveWaiverComparisonBundleCliTest {
    @Test
    void rendersThreeStageStatesAndPreservesNoRecommendationBoundary() {
        var candidate = new SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry(
            "c", "Candidate", "RB", "bc", true, 12, 2, 10,
            "ADD_ONLY", "CHI", "Active", null, "RB2", 2);
        var roster = new SleeperLiveWaiverComparisonExecutionBundle.RosterEntry(
            "r", "Roster", "RB", "BENCH", "br", true);
        var source = new SleeperLiveWaiverComparisonExecutionBundle.SourceComparison(
            "nflverse", java.time.LocalDate.of(2026, 9, 1), java.time.LocalDate.of(2026, 9, 1),
            10.0, 8.0, List.of("rush_yd"), List.of("rush_yd"), List.of(), List.of(),
            "COMPARABLE_COMMON_SOURCE", "CANDIDATE_HIGHER");
        var pair = new SleeperLiveWaiverComparisonExecutionBundle.PairComparison(
            candidate, roster,
            SleeperLiveWaiverComparisonExecutionBundle.PairState.CANDIDATE_DIRECTIONALLY_SUPPORTED,
            List.of("nflverse"), List.of(source), List.of());
        var pairCounts = new SleeperLiveWaiverComparisonExecutionBundle.PairCounts(1, 0, 0, 0, 0, 0, 0);
        var candidateComparison = new SleeperLiveWaiverComparisonExecutionBundle.CandidateComparison(
            candidate,
            SleeperLiveWaiverComparisonExecutionBundle.CandidateExecutionState.HISTORICAL_PAIRWISE_COMPARISON_EXECUTED,
            List.of("r"), List.of(pair));
        var comparisons = new SleeperLiveWaiverComparisonExecutionBundle.ComparisonReport(
            SleeperLiveWaiverComparisonExecutionBundle.BF615_POLICY_ID,
            "M", "W", "L", "owner", "S", 1, 1, 1, 1, 1,
            pairCounts, List.of(candidateComparison), List.of(pair),
            SleeperLiveWaiverComparisonExecutionBundle.ComparisonState.COMPARISONS_EXECUTED_EVIDENCE_ONLY);
        var decision = new SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistDecision(
            candidate,
            SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistState.HISTORICAL_DIRECTIONAL_SHORTLIST,
            pairCounts, List.of("r"), List.of("r"));
        var entry = new SleeperLiveWaiverComparisonExecutionBundle.ShortlistEntry(
            candidate, SleeperLiveWaiverComparisonExecutionBundle.ShortlistLane.HISTORICAL_DIRECTIONAL,
            List.of("r"), List.of("r"), pairCounts);
        var shortlist = new SleeperLiveWaiverComparisonExecutionBundle.ShortlistReport(
            SleeperLiveWaiverComparisonExecutionBundle.BF616_POLICY_ID,
            "M", "L", "owner", 1, 1, 0, List.of(decision), List.of(entry),
            SleeperLiveWaiverComparisonExecutionBundle.ShortlistState.SHORTLIST_BUILT_EVIDENCE_ONLY);
        var readiness = new SleeperLiveWaiverComparisonExecutionBundle.DecisionReadinessReport(
            SleeperLiveWaiverComparisonExecutionBundle.BF617_POLICY_ID,
            "M", "L", "owner", 1, 1, 0, List.of(entry),
            SleeperLiveWaiverComparisonExecutionBundle.FinalDecisionAuthorizationState.READY_FOR_FINAL_WAIVER_DECISION_METHOD);
        var report = new SleeperLiveWaiverComparisonExecutionBundle.BundleReport(
            methodology(), comparisons, shortlist, readiness);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerSleeperLiveWaiverComparisonBundleCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains("BF-615 state: COMPARISONS_EXECUTED_EVIDENCE_ONLY"));
        assertTrue(output.contains("BF-616 state: SHORTLIST_BUILT_EVIDENCE_ONLY"));
        assertTrue(output.contains("BF-617 state: READY_FOR_FINAL_WAIVER_DECISION_METHOD"));
        assertTrue(output.contains("this is NOT a rank"));
        assertTrue(output.contains("does not rank the shortlist"));
        assertTrue(output.contains("does not"));
        assertTrue(output.contains("recommend an add/drop transaction"));
        assertFalse(output.contains("FAAB recommendation:"));
    }

    private static SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyReport methodology() {
        Map<String, Double> scoring = new LinkedHashMap<>();
        scoring.put("pass_yd", 0.04); scoring.put("pass_td", 4.0);
        scoring.put("rush_yd", 0.1); scoring.put("rush_td", 6.0);
        scoring.put("rec", 1.0); scoring.put("rec_yd", 0.1); scoring.put("rec_td", 6.0);
        return new SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyReport(
            SleeperLiveWaiverCandidateRosterComparisonMethodology.POLICY_ID,
            "L", "owner", "M", "W", "S", 2026, "in_season", 1, 1,
            1, 1, 1, 0, 1, 0, 1, 0, 0, 1, 0,
            List.of(), scoring, Map.of(), Map.of(),
            List.of("pass_td", "pass_yd", "rec", "rec_td", "rec_yd", "rush_td", "rush_yd"),
            List.of("BENCH", "RESERVE"),
            "EXACT_POSITION_ONLY",
            "COMMON_2025_SOURCE_ONLY_ALL_COMMON_SOURCES_MUST_AGREE",
            "SUPPORTED_LEAGUE_SCORING_SUBTOTAL_PER_GAME_NOT_FULL_FANTASY_POINTS",
            "NEWCOMER_REVIEW_NONNUMERIC",
            "MISSING_TARGET_PRIOR_PRODUCTION_PROTECTED_UNRESOLVED",
            "MARKET_TEAM_STATUS_INJURY_DEPTH_DESCRIPTIVE_ONLY_NOT_ARITHMETIC",
            SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyState.METHODOLOGY_FROZEN_NO_SELECTION);
    }
}
