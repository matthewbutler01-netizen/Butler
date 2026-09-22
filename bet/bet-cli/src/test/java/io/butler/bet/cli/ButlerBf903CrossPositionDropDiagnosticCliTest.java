package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ButlerBf903CrossPositionDropDiagnosticCliTest {

    @Test
    void identifiesUniqueStrictWinnerAcrossCompatibleCompleteTransactions() {
        var weak = option("A", "D1", 2.0d);
        var strong = option("B", "D2", 5.0d);

        assertEquals(
            ButlerBf903CrossPositionDropDiagnosticCli.DiagnosticState.UNIQUE_STRICT_TRANSACTION_WINNER,
            ButlerBf903CrossPositionDropDiagnosticCli.state(List.of(weak, strong)));
        assertEquals(
            "B",
            ButlerBf903CrossPositionDropDiagnosticCli.uniqueWinner(List.of(weak, strong)).addSleeperId());
    }

    @Test
    void equalImprovementRemainsUnresolved() {
        var left = option("A", "D1", 3.0d);
        var right = option("B", "D2", 3.0d);

        assertEquals(
            ButlerBf903CrossPositionDropDiagnosticCli.DiagnosticState.NO_UNIQUE_STRICT_TRANSACTION_WINNER,
            ButlerBf903CrossPositionDropDiagnosticCli.state(List.of(left, right)));
        assertNull(ButlerBf903CrossPositionDropDiagnosticCli.uniqueWinner(List.of(left, right)));
    }

    @Test
    void incompatibleSourceSetsFailClosed() {
        var left = option("A", "D1", 3.0d);
        var right = new ButlerBf903CrossPositionDropDiagnosticCli.Option(
            "B", "Add B", "WR",
            "D2", "Drop D2", "RB",
            Map.of("other", 4.0d),
            Map.of("other", List.of("rec", "rec_yd")));

        assertEquals(
            ButlerBf903CrossPositionDropDiagnosticCli.DiagnosticState.INCOMPATIBLE_EVIDENCE,
            ButlerBf903CrossPositionDropDiagnosticCli.state(List.of(left, right)));
    }

    private static ButlerBf903CrossPositionDropDiagnosticCli.Option option(
        String add,
        String drop,
        double improvement) {
        return new ButlerBf903CrossPositionDropDiagnosticCli.Option(
            add, "Add " + add, "WR",
            drop, "Drop " + drop, "RB",
            Map.of("nflverse", improvement),
            Map.of("nflverse", List.of("rec", "rec_yd")));
    }
}
