package io.butler.bet.cli;

import io.butler.bet.intelligence.TradeMarketEdgePolicy;
import io.butler.bet.intelligence.TradeRecommendationFlexibleTransitionMaterialLossPolicy;
import io.butler.bet.intelligence.TradeRecommendationPolicy;
import io.butler.bet.intelligence.TradeRecommendationVetoPolicy;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeRecommendationV5MarketUnavailableContractTest {
    @Test
    void marketUnavailableFailsClosedWithoutStrategicVeto() {
        var status = new ButlerTradeRecommendationCli.FlexibleEvidenceStatus(
            false, true, true, true, true);
        var evidence = new TradeRecommendationFlexibleTransitionMaterialLossPolicy.EvidenceGate(
            true, true, true, true);
        var veto = new ButlerTradeRecommendationV5Cli.V5VetoEvaluation(
            false, TradeRecommendationVetoPolicy.VetoState.CLEAR, List.of());

        var packageRecommendation = TradeRecommendationFlexibleTransitionMaterialLossPolicy.classify(
            TradeMarketEdgePolicy.Direction.UNAVAILABLE,
            evidence,
            veto.state());
        var action = TradeTeamPerspectiveRecommendationPolicy.classify(
            packageRecommendation,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM);

        assertFalse(status.complete());
        assertFalse(veto.evaluated());
        assertEquals(TradeRecommendationVetoPolicy.VetoState.CLEAR, veto.state());
        assertTrue(veto.reasons().isEmpty());
        assertEquals(TradeRecommendationPolicy.Recommendation.INCONCLUSIVE, packageRecommendation);
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Action.INCONCLUSIVE, action);
        assertEquals(
            "unavailable governed evidence: market direction",
            ButlerTradeRecommendationCli.formatInconclusiveReason(status));
    }

    @Test
    void unevaluatedV5VetoCannotMasqueradeAsBlocked() {
        assertThrows(IllegalArgumentException.class, () ->
            new ButlerTradeRecommendationV5Cli.V5VetoEvaluation(
                false, TradeRecommendationVetoPolicy.VetoState.BLOCKED, List.of()));
    }
}
