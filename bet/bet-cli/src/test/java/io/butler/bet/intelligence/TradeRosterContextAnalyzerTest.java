package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeRosterContextAnalyzerTest {
    @Test
    void resolvesSingleFantasyTeamForTradeSide() {
        var side = new TradeValueAnalyzer.TradeSide(List.of(
            player("p1", "t1"), player("p2", "t1")), 200.0, 2, 0);

        assertEquals("t1", TradeRosterContextAnalyzer.singleTeamId(side, "side A"));
    }

    @Test
    void rejectsSideThatSpansMultipleFantasyTeams() {
        var side = new TradeValueAnalyzer.TradeSide(List.of(
            player("p1", "t1"), player("p2", "t2")), 200.0, 2, 0);

        assertThrows(IllegalArgumentException.class,
            () -> TradeRosterContextAnalyzer.singleTeamId(side, "side A"));
    }

    @Test
    void rejectsMissingTeamIdentityAndEmptySide() {
        var missingTeam = new TradeValueAnalyzer.TradePlayer(
            "p1", "Player", "WR", "CHI", "", "Team", 100.0, LocalDate.of(2026, 9, 1));
        var side = new TradeValueAnalyzer.TradeSide(List.of(missingTeam), 100.0, 1, 0);

        assertThrows(IllegalStateException.class,
            () -> TradeRosterContextAnalyzer.singleTeamId(side, "side A"));
        assertThrows(IllegalArgumentException.class,
            () -> TradeRosterContextAnalyzer.singleTeamId(
                new TradeValueAnalyzer.TradeSide(List.of(), 0.0, 0, 0), "side A"));
    }

    private static TradeValueAnalyzer.TradePlayer player(String id, String teamId) {
        return new TradeValueAnalyzer.TradePlayer(
            id, id, "WR", "CHI", teamId, "Team " + teamId, 100.0, LocalDate.of(2026, 9, 1));
    }
}
