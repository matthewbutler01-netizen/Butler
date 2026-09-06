package io.butler.bet.sleeper;

import io.butler.bet.domain.ProviderPlayerWeekPointsEvidence;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderPointsEvidenceReconcilerTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 6);

    @Test
    void acceptsNumericallyEqualPointsWithDifferentScale() {
        var expected = row("e1", "t1", 1, "p1", "7.0", "hist");
        var actual = row("e1", "t1", 1, "p1", "7.00", "hist");

        assertDoesNotThrow(() -> ProviderPointsEvidenceReconciler.reconcile(
            List.of(expected), List.of(actual)));
    }

    @Test
    void rejectsTrueNumericMismatch() {
        var expected = row("e1", "t1", 1, "p1", "7.0", "hist");
        var actual = row("e1", "t1", 1, "p1", "7.01", "hist");

        var error = assertThrows(IllegalStateException.class,
            () -> ProviderPointsEvidenceReconciler.reconcile(List.of(expected), List.of(actual)));
        assertTrue(error.getMessage().contains("mismatch=points"));
    }

    @Test
    void rejectsProvenanceMismatch() {
        var expected = row("e1", "t1", 1, "p1", "7.0", "hist");
        var actual = row("e1", "t1", 1, "p1", "7.0", "other-hist");

        var error = assertThrows(IllegalStateException.class,
            () -> ProviderPointsEvidenceReconciler.reconcile(List.of(expected), List.of(actual)));
        assertTrue(error.getMessage().contains("mismatch=providerLeagueId"));
    }

    @Test
    void rejectsNonDeterministicReadBackOrder() {
        var first = row("e1", "t1", 1, "p1", "7.0", "hist");
        var second = row("e2", "t1", 2, "p2", "8.0", "hist");

        var error = assertThrows(IllegalStateException.class,
            () -> ProviderPointsEvidenceReconciler.reconcile(
                List.of(first, second), List.of(second, first)));
        assertTrue(error.getMessage().contains("non-deterministic read-back order"));
    }

    private static ProviderPlayerWeekPointsEvidence row(
        String id, String teamId, int week, String playerId, String points, String providerLeagueId) {
        return new ProviderPlayerWeekPointsEvidence(
            id,
            "l1",
            teamId,
            "1",
            providerLeagueId,
            2025,
            week,
            playerId,
            new BigDecimal(points),
            "sleeper",
            "matchup.players_points",
            AS_OF);
    }
}
