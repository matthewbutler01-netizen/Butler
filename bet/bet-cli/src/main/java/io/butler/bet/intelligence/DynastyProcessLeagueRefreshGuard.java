package io.butler.bet.intelligence;

import java.util.Objects;

/**
 * Optional league-scoped safety gate for callers that require every rostered player in one
 * league to map before proceeding with a DynastyProcess-backed operation.
 */
public final class DynastyProcessLeagueRefreshGuard {
    private DynastyProcessLeagueRefreshGuard() {}

    public static void requireReady(DynastyProcessLeaguePreviewAnalyzer.LeaguePreview preview) {
        Objects.requireNonNull(preview, "preview must not be null");
        var readiness = DynastyProcessLeagueRefreshReadiness.classify(preview);
        if (readiness != DynastyProcessLeagueRefreshReadiness.Readiness.READY) {
            throw new IllegalArgumentException("DynastyProcess league refresh is " + readiness
                + ": league roster mapping must be READY before guarded use");
        }
    }
}
