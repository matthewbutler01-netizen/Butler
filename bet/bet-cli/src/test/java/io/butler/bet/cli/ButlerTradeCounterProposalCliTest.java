package io.butler.bet.cli;

import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterProposalCliTest {
    @Test
    void parsesProposalCoordinatesUsingDecisionContract() {
        var options = ButlerTradeCounterProposalCli.parse(new String[]{
            "trade", "counter-proposal", "l1", "2026",
            "player:p1,pick:k1", "p2,pick:k2", "side-b",
            "dynastyprocess", "--minimum-as-of", "2026-09-01"});

        assertEquals("l1", options.leagueId());
        assertEquals(2026, options.season());
        assertEquals(List.of("p1"), options.sideA().playerIds());
        assertEquals(List.of("k1"), options.sideA().draftPickIds());
        assertEquals(List.of("p2"), options.sideB().playerIds());
        assertEquals(List.of("k2"), options.sideB().draftPickIds());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_B_TEAM,
            options.perspective());
        assertEquals("dynastyprocess", options.source());
        assertEquals(LocalDate.of(2026, 9, 1), options.minimumAsOf());
    }

    @Test
    void rejectsDecisionCommandNameOnProposalSurface() {
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterProposalCli.parse(new String[]{
            "trade", "counter-decision", "l1", "2026", "p1", "p2", "side-a"}));
    }

    @Test
    void recognizesProposalCommandIndependently() {
        assertTrue(ButlerTradeCounterProposalCli.isCommand(new String[]{"trade", "counter-proposal"}));
    }
}
