package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperLiveWaiverCrossPositionTransactionEvidence;
import io.butler.bet.sleeper.SleeperLiveWaiverFinalRecommendationBundle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperLiveWaiverFinalRecommendationEvidenceCliTest {
    @Test
    void rendersSelectedAndAlternativeGovernedTransactionImprovements() {
        var selectedAdd = player("A", "Selected Add", "RB", "WAIVER_CANDIDATE");
        var selectedDrop = player("D1", "Selected Drop", "RB", "BENCH");
        var alternateAdd = player("C", "Alternate Add", "WR", "WAIVER_CANDIDATE");
        var alternateDrop = player("D2", "Alternate Drop", "WR", "BENCH");
        var evidence = new SleeperLiveWaiverCrossPositionTransactionEvidence.EvidenceReport(
            SleeperLiveWaiverCrossPositionTransactionEvidence.POLICY_ID,
            "L", "O", "M", "W",
            SleeperLiveWaiverFinalRecommendationBundle.SelectionState.UNIQUE_ADD_DROP_SELECTED,
            List.of(
                new SleeperLiveWaiverCrossPositionTransactionEvidence.TransactionEvidence(
                    selectedAdd, selectedDrop,
                    Map.of("nflverse", 6.0d),
                    Map.of("nflverse", List.of("rush_yd")), true),
                new SleeperLiveWaiverCrossPositionTransactionEvidence.TransactionEvidence(
                    alternateAdd, alternateDrop,
                    Map.of("nflverse", 3.0d),
                    Map.of("nflverse", List.of("rec_yd")), false)),
            SleeperLiveWaiverCrossPositionTransactionEvidence.EvidenceState.RECONCILED);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerSleeperLiveWaiverFinalRecommendationBundleCli.printEvidence(evidence);
        } finally {
            System.setOut(original);
        }

        String output = bytes.toString();
        assertTrue(output.contains("BF-625 - governed cross-position transaction evidence"));
        assertFalse(output.contains("—"));
        assertTrue(output.contains("Evidence state: RECONCILED"));
        assertTrue(output.contains("[SELECTED] RB | ADD Selected Add (Sleeper A) / DROP Selected Drop (Sleeper D1)"));
        assertTrue(output.contains("transaction-improvement=6.0000 supported-points/game"));
        assertTrue(output.contains("[ALTERNATIVE] WR | ADD Alternate Add (Sleeper C) / DROP Alternate Drop (Sleeper D2)"));
        assertTrue(output.contains("scoring-keys=[rush_yd]"));
        assertTrue(output.contains("does not rerank newcomers"));
    }

    private static SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer player(
        String id, String name, String position, String role) {
        return new SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer(
            id, name, position, role, null, null, null, null, null);
    }
}
