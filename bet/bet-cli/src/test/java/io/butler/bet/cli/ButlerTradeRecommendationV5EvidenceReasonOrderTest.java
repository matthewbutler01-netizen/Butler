package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ButlerTradeRecommendationV5EvidenceReasonOrderTest {
    @Test
    void missingEvidenceReasonUsesGovernedGateOrder() {
        var status = new ButlerTradeRecommendationCli.FlexibleEvidenceStatus(
            false, false, false, false, false);

        assertFalse(status.complete());
        assertEquals(
            "unavailable governed evidence: market direction, team posture, future capital, positional pressure, flexible pressure",
            ButlerTradeRecommendationCli.formatInconclusiveReason(status));
    }

    @Test
    void availableGatesAreOmittedWithoutReorderingMissingGates() {
        var status = new ButlerTradeRecommendationCli.FlexibleEvidenceStatus(
            true, false, true, false, false);

        assertFalse(status.complete());
        assertEquals(
            "unavailable governed evidence: team posture, positional pressure, flexible pressure",
            ButlerTradeRecommendationCli.formatInconclusiveReason(status));
    }
}
