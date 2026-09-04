package io.butler.bet.cli;

import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterDecisionCliTest {
    @Test
    void parsesMixedPackagesPerspectiveSourceAndFreshnessLikeRecommendation() {
        var options = ButlerTradeCounterDecisionCli.parse(new String[]{
            "trade", "counter-decision", "l1", "2026",
            "player:p1,pick:k1", "p2,pick:k2", "side-a",
            "dynastyprocess", "--minimum-as-of", "2026-09-01"});

        assertEquals("l1", options.leagueId());
        assertEquals(2026, options.season());
        assertEquals(List.of("p1"), options.sideA().playerIds());
        assertEquals(List.of("k1"), options.sideA().draftPickIds());
        assertEquals(List.of("p2"), options.sideB().playerIds());
        assertEquals(List.of("k2"), options.sideB().draftPickIds());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            options.perspective());
        assertEquals("dynastyprocess", options.source());
        assertEquals(LocalDate.of(2026, 9, 1), options.minimumAsOf());
    }

    @Test
    void acceptsShortPerspectiveAliasAndFreshnessWithoutSource() {
        var options = ButlerTradeCounterDecisionCli.parse(new String[]{
            "trade", "counter-decision", "l1", "2026", "p1", "p2", "b",
            "--minimum-as-of", "2026-09-01"});

        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_B_TEAM,
            options.perspective());
        assertEquals(null, options.source());
        assertEquals(LocalDate.of(2026, 9, 1), options.minimumAsOf());
    }

    @Test
    void rejectsMissingOrInvalidPerspective() {
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterDecisionCli.parse(new String[]{
            "trade", "counter-decision", "l1", "2026", "p1", "p2"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeCounterDecisionCli.parse(new String[]{
            "trade", "counter-decision", "l1", "2026", "p1", "p2", "owner"}));
    }

    @Test
    void commandIsIndependentFromCounterStrategicAndRecommendation() {
        assertTrue(ButlerTradeCounterDecisionCli.isCommand(new String[]{"trade", "counter-decision"}));
    }
}
