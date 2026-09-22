package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeRecommendationV6EvidenceTest {

    @Test
    void unavailablePostureRemainsVisibleButDoesNotBlockCompleteDecisionEvidence() {
        var status = new ButlerTradeRecommendationV6Cli.V6EvidenceStatus(
            true, false, true, true, true);

        assertTrue(status.complete());
        assertEquals(
            "Evidence gates: market-direction=true future-capital=true positional-pressure=true "
                + "flexible-pressure=true advisory-posture=false",
            ButlerTradeRecommendationV6Cli.formatEvidenceGates(status));
    }

    @Test
    void inconclusiveReasonOmitsAdvisoryPosture() {
        var postureOnlyMissing = new ButlerTradeRecommendationV6Cli.V6EvidenceStatus(
            true, false, true, true, true);
        assertEquals(
            "required governed decision evidence is incomplete",
            ButlerTradeRecommendationV6Cli.formatInconclusiveReason(postureOnlyMissing));

        var governedMissing = new ButlerTradeRecommendationV6Cli.V6EvidenceStatus(
            false, false, false, true, false);
        assertFalse(governedMissing.complete());
        assertEquals(
            "unavailable governed evidence: market direction, future capital, flexible pressure",
            ButlerTradeRecommendationV6Cli.formatInconclusiveReason(governedMissing));
    }
}
