package io.butler.bet.intelligence;

import java.util.Objects;

/**
 * Optional safety gate for callers that require a completely mapped DynastyProcess dataset
 * before allowing persistence. This does not change refresh behavior by itself; callers must
 * explicitly opt into the guard.
 */
public final class DynastyProcessRefreshGuard {
    private DynastyProcessRefreshGuard() {}

    public static void requireReady(DynastyProcessValueImporter.ProviderDiagnostics diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        var readiness = DynastyProcessRefreshReadiness.classify(diagnostics);
        if (readiness != DynastyProcessRefreshReadiness.Readiness.READY) {
            throw new IllegalArgumentException("DynastyProcess refresh is " + readiness
                + ": provider mapping must be READY before guarded persistence");
        }
    }
}
