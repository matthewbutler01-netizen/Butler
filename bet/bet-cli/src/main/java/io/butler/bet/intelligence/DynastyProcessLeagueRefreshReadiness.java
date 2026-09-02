package io.butler.bet.intelligence;

import java.util.Objects;

/**
 * Classifies DynastyProcess preview impact for one league without inventing a percentage threshold.
 */
public final class DynastyProcessLeagueRefreshReadiness {
    private DynastyProcessLeagueRefreshReadiness() {}

    public static Readiness classify(DynastyProcessLeaguePreviewAnalyzer.LeaguePreview preview) {
        Objects.requireNonNull(preview, "preview must not be null");
        if (preview.rosteredPlayers() == 0) return Readiness.UNAVAILABLE;
        if (preview.matchedPlayers() == 0) return Readiness.BLOCKED;
        if (preview.unmatchedPlayers() > 0 || preview.ineligiblePlayers() > 0) return Readiness.PARTIAL;
        return Readiness.READY;
    }

    public enum Readiness {
        UNAVAILABLE,
        BLOCKED,
        PARTIAL,
        READY
    }
}
