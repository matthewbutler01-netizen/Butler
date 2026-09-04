package io.butler.bet.cli;

import io.butler.bet.intelligence.TradeRecommendationPolicy;
import io.butler.bet.intelligence.TradeRecommendationVetoPolicy;
import io.butler.bet.intelligence.TradeStrategicVetoDetector;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeRecommendationCliTest {
    @Test
    void parsesExplicitSideAPerspective() {
        var options = ButlerTradeRecommendationCli.parse(new String[]{
            "trade", "recommendation", "l1", "2026", "player:p1,pick:d1", "p2", "side-a"});

        assertEquals("l1", options.leagueId());
        assertEquals(2026, options.season());
        assertEquals(List.of("p1"), options.sideA().playerIds());
        assertEquals(List.of("d1"), options.sideA().draftPickIds());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM, options.perspective());
        assertNull(options.source());
        assertNull(options.minimumAsOf());
    }

    @Test
    void parsesSideBSourceAndFreshnessBoundary() {
        var options = ButlerTradeRecommendationCli.parse(new String[]{
            "trade", "recommendation", "l1", "2026", "p1", "pick:d2", "side-b",
            "dynastyprocess", "--minimum-as-of", "2026-09-01"});

        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_B_TEAM, options.perspective());
        assertEquals("dynastyprocess", options.source());
        assertEquals(LocalDate.of(2026, 9, 1), options.minimumAsOf());
    }

    @Test
    void explainsEvidenceGateState() {
        var status = new ButlerTradeRecommendationCli.EvidenceStatus(true, false, true, false);

        assertEquals(
            "Evidence gates: market-direction=true posture=false future-capital=true positional-pressure=false",
            ButlerTradeRecommendationCli.formatEvidenceGates(status));
        assertEquals(
            "unavailable governed evidence: team posture, positional pressure",
            ButlerTradeRecommendationCli.formatInconclusiveReason(status));
    }

    @Test
    void formatsStrategicVetoReasons() {
        assertEquals(
            "low future capital: sending future pick(s) without receiving a future pick",
            ButlerTradeRecommendationCli.formatVetoReason(new TradeStrategicVetoDetector.VetoReason(
                TradeStrategicVetoDetector.ReasonCode.LOW_FUTURE_CAPITAL_OUTGOING_PICKS_WITHOUT_PICK_RETURN, null)));
        assertEquals(
            "QB pressure: sending QB without receiving QB",
            ButlerTradeRecommendationCli.formatVetoReason(new TradeStrategicVetoDetector.VetoReason(
                TradeStrategicVetoDetector.ReasonCode.POSITION_PRESSURE_OUTGOING_WITHOUT_SAME_POSITION_RETURN, "qb")));
    }

    @Test
    void completeEvidenceStatusRequiresEveryGate() {
        assertTrue(new ButlerTradeRecommendationCli.EvidenceStatus(true, true, true, true).complete());
        assertEquals(false, new ButlerTradeRecommendationCli.EvidenceStatus(false, true, true, true).complete());
    }

    @Test
    void locksRecommendationPolicyAndActionVocabulary() {
        assertEquals("trade-recommendation-v1-conservative-evidence-first", TradeRecommendationPolicy.POLICY_ID);
        assertEquals("trade-recommendation-v2-market-first-strategic-veto", TradeRecommendationVetoPolicy.POLICY_ID);
        assertEquals("trade-strategic-veto-v1-explicit-weakness-protection", TradeStrategicVetoDetector.POLICY_ID);
        assertEquals("trade-team-perspective-v1-explicit-owner", TradeTeamPerspectiveRecommendationPolicy.POLICY_ID);
        assertEquals(List.of("ACCEPT", "REJECT", "HOLD", "INCONCLUSIVE"),
            java.util.Arrays.stream(TradeTeamPerspectiveRecommendationPolicy.Action.values())
                .map(Enum::name)
                .toList());
    }

    @Test
    void rejectsMissingOrInvalidPerspective() {
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeRecommendationCli.parse(new String[]{
            "trade", "recommendation", "l1", "2026", "p1", "p2"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeRecommendationCli.parse(new String[]{
            "trade", "recommendation", "l1", "2026", "p1", "p2", "mine"}));
    }

    @Test
    void recognizesRecommendationCommand() {
        assertTrue(ButlerTradeRecommendationCli.isCommand(new String[]{
            "trade", "recommendation", "l1", "2026", "p1", "p2", "side-a"}));
    }
}
