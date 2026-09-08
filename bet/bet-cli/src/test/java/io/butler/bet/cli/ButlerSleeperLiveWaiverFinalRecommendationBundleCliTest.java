package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperLiveWaiverFinalRecommendationBundle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperLiveWaiverFinalRecommendationBundleCliTest {
    @Test
    void rendersReadOnlyActionableRecommendationWithoutFaabOrConfidenceClaim() {
        var methodology = new SleeperLiveWaiverFinalRecommendationBundle.MethodologyReport(
            SleeperLiveWaiverFinalRecommendationBundle.BF618_POLICY_ID,
            "M", 2, 1, List.of("RB"), "STRICT_DOMINANCE", "COMMON_SOURCE", "NO_CROSS_POSITION",
            "NEWCOMER_NONNUMERIC", "SUPPORTED_DROP_ONLY", "PROTECTED_NEVER_DROP",
            SleeperLiveWaiverFinalRecommendationBundle.MethodologyState.FINAL_SELECTION_METHOD_FROZEN);
        var add = new SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer(
            "A", "Add Player", "RB", "WAIVER_CANDIDATE", "IND", "Active", null, "RB", 2);
        var drop = new SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer(
            "D", "Drop Player", "RB", "BENCH", null, null, null, null, null);
        var selection = new SleeperLiveWaiverFinalRecommendationBundle.SelectionReport(
            SleeperLiveWaiverFinalRecommendationBundle.BF619_POLICY_ID,
            "M", List.of("A", "B"), List.of(), add, drop,
            SleeperLiveWaiverFinalRecommendationBundle.SelectionState.UNIQUE_ADD_DROP_SELECTED);
        var report = new SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport(
            SleeperLiveWaiverFinalRecommendationBundle.BF620_POLICY_ID,
            "L", "O", "M", "W", "S", 1, methodology, selection,
            2026, "in_season", 1, add, drop, List.of(),
            SleeperLiveWaiverFinalRecommendationBundle.RecommendationState.RECOMMEND_ADD_DROP);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerSleeperLiveWaiverFinalRecommendationBundleCli.print(report);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains("BF-618 state: FINAL_SELECTION_METHOD_FROZEN"));
        assertTrue(output.contains("Selection state: UNIQUE_ADD_DROP_SELECTED"));
        assertTrue(output.contains("Recommendation state: RECOMMEND_ADD_DROP"));
        assertTrue(output.contains("BUTLER RECOMMENDATION: ADD Add Player (Sleeper A) / DROP Drop Player (Sleeper D)"));
        assertTrue(output.contains("does not submit a Sleeper transaction"));
        assertTrue(output.contains("does not") && output.contains("FAAB"));
    }
}
