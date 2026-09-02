package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynastyProcessLeagueRefreshGuardTest {
    @Test
    void allowsReadyLeaguePreview() {
        assertDoesNotThrow(() -> DynastyProcessLeagueRefreshGuard.requireReady(preview(3, 3, 0, 0)));
    }

    @Test
    void blocksPartialBlockedAndUnavailableLeaguePreviews() {
        assertThrows(IllegalArgumentException.class,
            () -> DynastyProcessLeagueRefreshGuard.requireReady(preview(3, 2, 1, 0)));
        assertThrows(IllegalArgumentException.class,
            () -> DynastyProcessLeagueRefreshGuard.requireReady(preview(2, 0, 2, 0)));
        assertThrows(IllegalArgumentException.class,
            () -> DynastyProcessLeagueRefreshGuard.requireReady(preview(0, 0, 0, 0)));
    }

    private static DynastyProcessLeaguePreviewAnalyzer.LeaguePreview preview(
        int rostered, int matched, int unmatched, int ineligible) {
        return new DynastyProcessLeaguePreviewAnalyzer.LeaguePreview(
            "league", LocalDate.of(2026, 8, 28), rostered, matched, unmatched, ineligible,
            unmatched + ineligible > 0 ? 1 : 0, List.of());
    }
}
