package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynastyProcessLeagueRefreshReadinessTest {
    @Test
    void classifiesUnavailableWhenLeagueHasNoRosteredPlayers() {
        assertEquals(DynastyProcessLeagueRefreshReadiness.Readiness.UNAVAILABLE,
            DynastyProcessLeagueRefreshReadiness.classify(preview(0, 0, 0, 0)));
    }

    @Test
    void classifiesBlockedWhenNoRosteredPlayersMap() {
        assertEquals(DynastyProcessLeagueRefreshReadiness.Readiness.BLOCKED,
            DynastyProcessLeagueRefreshReadiness.classify(preview(2, 0, 2, 0)));
    }

    @Test
    void classifiesPartialWhenAnyLeagueRosterGapRemains() {
        assertEquals(DynastyProcessLeagueRefreshReadiness.Readiness.PARTIAL,
            DynastyProcessLeagueRefreshReadiness.classify(preview(3, 2, 1, 0)));
        assertEquals(DynastyProcessLeagueRefreshReadiness.Readiness.PARTIAL,
            DynastyProcessLeagueRefreshReadiness.classify(preview(3, 2, 0, 1)));
    }

    @Test
    void classifiesReadyOnlyWhenEveryRosteredPlayerMaps() {
        assertEquals(DynastyProcessLeagueRefreshReadiness.Readiness.READY,
            DynastyProcessLeagueRefreshReadiness.classify(preview(3, 3, 0, 0)));
    }

    private static DynastyProcessLeaguePreviewAnalyzer.LeaguePreview preview(
        int rostered, int matched, int unmatched, int ineligible) {
        return new DynastyProcessLeaguePreviewAnalyzer.LeaguePreview(
            "league", LocalDate.of(2026, 8, 28), rostered, matched, unmatched, ineligible,
            unmatched + ineligible > 0 ? 1 : 0, List.of());
    }
}
