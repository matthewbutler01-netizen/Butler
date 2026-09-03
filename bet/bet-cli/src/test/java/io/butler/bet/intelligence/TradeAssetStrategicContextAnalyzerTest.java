package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeAssetStrategicContextAnalyzerTest {
    @Test
    void resolvesMixedPlayerAndPickPackageToCurrentOwnerTeam() {
        var side = new TradeAssetAnalyzer.TradeSide(
            List.of(player("p1", "t1", "Team One")),
            List.of(pick("d1", "t1", "Team One")),
            200.0, 1, 0, 1, 0);

        var identity = TradeAssetStrategicContextAnalyzer.resolveSideTeam(side, "side A");
        assertEquals("t1", identity.teamId());
        assertEquals("Team One", identity.teamName());
    }

    @Test
    void resolvesPickOnlyPackageToCurrentOwnerTeam() {
        var side = new TradeAssetAnalyzer.TradeSide(
            List.of(), List.of(pick("d1", "t2", "Team Two")),
            100.0, 0, 0, 1, 0);

        assertEquals("t2", TradeAssetStrategicContextAnalyzer.resolveSideTeam(side, "side A").teamId());
    }

    @Test
    void rejectsPackageSpanningMultipleCurrentOwners() {
        var side = new TradeAssetAnalyzer.TradeSide(
            List.of(player("p1", "t1", "Team One")),
            List.of(pick("d1", "t2", "Team Two")),
            200.0, 1, 0, 1, 0);

        assertThrows(IllegalArgumentException.class,
            () -> TradeAssetStrategicContextAnalyzer.resolveSideTeam(side, "side A"));
    }

    private static TradeAssetAnalyzer.TradePlayer player(String id, String teamId, String teamName) {
        return new TradeAssetAnalyzer.TradePlayer(
            id, id, "WR", "CHI", teamId, teamName, 100.0, LocalDate.of(2026, 9, 1), false);
    }

    private static TradeAssetAnalyzer.TradeDraftPick pick(String id, String ownerTeamId, String ownerTeamName) {
        return new TradeAssetAnalyzer.TradeDraftPick(
            id, 2027, 1, "2027 1st", "orig", "Original Team", ownerTeamId, ownerTeamName,
            null, 100.0, LocalDate.of(2026, 9, 1), false);
    }
}
