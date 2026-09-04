package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeProtectedValueFlowAnalyzerTest {
    @Test
    void computesDraftPickValueLossWithoutClassifyingMateriality() {
        var flow = TradeProtectedValueFlowAnalyzer.draftPickFlow(
            side(List.of(), List.of(pick("out-1", 80.0), pick("out-2", 20.0))),
            side(List.of(), List.of(pick("in-1", 35.0))));

        assertEquals(100.0, flow.outgoingValue());
        assertEquals(35.0, flow.incomingValue());
        assertEquals(65.0, flow.netLoss());
        assertEquals(0.65, flow.lossFraction(), 0.000001);
    }

    @Test
    void computesSamePositionValueFlowOnly() {
        var flow = TradeProtectedValueFlowAnalyzer.positionFlow("wr",
            side(List.of(player("wr-out", "WR", 90.0), player("rb-out", "RB", 40.0)), List.of()),
            side(List.of(player("wr-in", "WR", 60.0), player("te-in", "TE", 50.0)), List.of()));

        assertEquals(90.0, flow.outgoingValue());
        assertEquals(60.0, flow.incomingValue());
        assertEquals(30.0, flow.netLoss());
        assertEquals(1.0 / 3.0, flow.lossFraction(), 0.000001);
    }

    @Test
    void replacementAtOrAboveOutgoingValueHasNoLoss() {
        var equal = new TradeProtectedValueFlowAnalyzer.ValueFlow(50.0, 50.0);
        var upgrade = new TradeProtectedValueFlowAnalyzer.ValueFlow(50.0, 75.0);

        assertEquals(0.0, equal.netLoss());
        assertEquals(0.0, equal.lossFraction());
        assertEquals(0.0, upgrade.netLoss());
        assertEquals(0.0, upgrade.lossFraction());
    }

    @Test
    void zeroOutgoingProtectedValueHasZeroLossFraction() {
        var flow = new TradeProtectedValueFlowAnalyzer.ValueFlow(0.0, 25.0);
        assertEquals(0.0, flow.lossFraction());
    }

    @Test
    void failsClosedOnMissingOrStaleProtectedValues() {
        assertThrows(IllegalArgumentException.class, () -> TradeProtectedValueFlowAnalyzer.positionFlow("WR",
            side(List.of(player("missing", "WR", null)), List.of()),
            side(List.of(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> TradeProtectedValueFlowAnalyzer.draftPickFlow(
            side(List.of(), List.of(stalePick("stale", 25.0))),
            side(List.of(), List.of())));
    }

    private static TradeAssetAnalyzer.TradeSide side(
        List<TradeAssetAnalyzer.TradePlayer> players,
        List<TradeAssetAnalyzer.TradeDraftPick> picks) {
        return new TradeAssetAnalyzer.TradeSide(players, picks, 100.0,
            (int) players.stream().filter(TradeAssetAnalyzer.TradePlayer::valued).count(),
            (int) players.stream().filter(player -> !player.valued()).count(),
            (int) picks.stream().filter(TradeAssetAnalyzer.TradeDraftPick::valued).count(),
            (int) picks.stream().filter(pick -> !pick.valued()).count());
    }

    private static TradeAssetAnalyzer.TradePlayer player(String id, String position, Double value) {
        return new TradeAssetAnalyzer.TradePlayer(
            id, id, position, "NFL", "t1", "Team One", value, LocalDate.of(2026, 9, 1), false);
    }

    private static TradeAssetAnalyzer.TradeDraftPick pick(String id, Double value) {
        return new TradeAssetAnalyzer.TradeDraftPick(
            id, 2027, 1, "2027 1st", "t1", "Team One", "t1", "Team One",
            null, value, LocalDate.of(2026, 9, 1), false);
    }

    private static TradeAssetAnalyzer.TradeDraftPick stalePick(String id, Double value) {
        return new TradeAssetAnalyzer.TradeDraftPick(
            id, 2027, 1, "2027 1st", "t1", "Team One", "t1", "Team One",
            null, value, LocalDate.of(2026, 8, 1), true);
    }
}
