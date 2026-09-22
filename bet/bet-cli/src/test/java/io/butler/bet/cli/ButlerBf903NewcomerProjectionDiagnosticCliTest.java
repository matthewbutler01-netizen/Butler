package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerBf903NewcomerProjectionDiagnosticCliTest {

    @Test
    void identifiesPositiveProjectedNewcomerTransaction() {
        var newcomers = List.of(
            new ButlerBf903NewcomerProjectionDiagnosticCli.Newcomer("N", "Newcomer", "RB"));
        var drops = List.of(
            new ButlerBf903NewcomerProjectionDiagnosticCli.Drop("D", "Drop", "WR", "BENCH"));

        var diagnostic = ButlerBf903NewcomerProjectionDiagnosticCli.evaluate(
            newcomers,
            drops,
            Map.of("N", new BigDecimal("9.50"), "D", new BigDecimal("6.25")),
            Map.of());

        assertEquals(
            ButlerBf903NewcomerProjectionDiagnosticCli.DiagnosticState.CURRENT_PROJECTION_LANE_AVAILABLE,
            diagnostic.state());
        assertEquals(1, diagnostic.newcomersWithProjection());
        assertEquals(1, diagnostic.dropsWithProjection());
        assertEquals(1, diagnostic.positiveTransactions().size());
        assertEquals(new BigDecimal("3.25"),
            diagnostic.positiveTransactions().get(0).projectedDelta());
    }

    @Test
    void missingNewcomerProjectionFailsClosedWithoutInventingZero() {
        var newcomers = List.of(
            new ButlerBf903NewcomerProjectionDiagnosticCli.Newcomer("N", "Newcomer", "RB"));
        var drops = List.of(
            new ButlerBf903NewcomerProjectionDiagnosticCli.Drop("D", "Drop", "WR", "BENCH"));

        var diagnostic = ButlerBf903NewcomerProjectionDiagnosticCli.evaluate(
            newcomers,
            drops,
            Map.of("D", new BigDecimal("6.25")),
            Map.of("N", "exact projection row unavailable"));

        assertEquals(
            ButlerBf903NewcomerProjectionDiagnosticCli.DiagnosticState.NO_NEWCOMER_PROJECTION_COVERAGE,
            diagnostic.state());
        assertEquals(0, diagnostic.positiveTransactions().size());
    }

    @Test
    void nonpositiveDeltaDoesNotBecomeActionable() {
        var newcomers = List.of(
            new ButlerBf903NewcomerProjectionDiagnosticCli.Newcomer("N", "Newcomer", "RB"));
        var drops = List.of(
            new ButlerBf903NewcomerProjectionDiagnosticCli.Drop("D", "Drop", "WR", "RESERVE"));

        var diagnostic = ButlerBf903NewcomerProjectionDiagnosticCli.evaluate(
            newcomers,
            drops,
            Map.of("N", new BigDecimal("4.0"), "D", new BigDecimal("6.0")),
            Map.of());

        assertEquals(
            ButlerBf903NewcomerProjectionDiagnosticCli.DiagnosticState.CURRENT_PROJECTIONS_DO_NOT_SUPPORT_NEWCOMER_TRANSACTION,
            diagnostic.state());
        assertEquals(0, diagnostic.positiveTransactions().size());
    }
}
