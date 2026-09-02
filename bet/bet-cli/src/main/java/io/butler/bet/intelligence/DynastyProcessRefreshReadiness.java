package io.butler.bet.intelligence;

import java.util.Objects;

/**
 * Classifies DynastyProcess provider diagnostics without inventing a coverage threshold.
 * A provider refresh is READY only when every upstream value row maps and there are no
 * ambiguous exact-identity crosswalk entries. Any non-zero mapping coverage with gaps is
 * PARTIAL; zero mapped provider rows is BLOCKED.
 */
public final class DynastyProcessRefreshReadiness {
    private DynastyProcessRefreshReadiness() {}

    public static Readiness classify(DynastyProcessValueImporter.ProviderDiagnostics diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        if (diagnostics.providerRowsMapped() == 0) return Readiness.BLOCKED;
        if (diagnostics.providerRowsUnmapped() > 0 || diagnostics.ambiguousIdentityMappings() > 0) {
            return Readiness.PARTIAL;
        }
        return Readiness.READY;
    }

    public enum Readiness {
        BLOCKED,
        PARTIAL,
        READY
    }
}
