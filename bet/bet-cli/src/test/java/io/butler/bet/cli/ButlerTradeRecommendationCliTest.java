package io.butler.bet.cli;

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
