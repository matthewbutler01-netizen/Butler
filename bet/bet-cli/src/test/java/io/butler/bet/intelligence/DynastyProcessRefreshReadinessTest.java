package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import static io.butler.bet.intelligence.DynastyProcessRefreshReadiness.Readiness.BLOCKED;
import static io.butler.bet.intelligence.DynastyProcessRefreshReadiness.Readiness.PARTIAL;
import static io.butler.bet.intelligence.DynastyProcessRefreshReadiness.Readiness.READY;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DynastyProcessRefreshReadinessTest {
    @Test
    void readyWhenEveryProviderRowMapsWithoutAmbiguousIdentityEntries() {
        var diagnostics = diagnostics(10, 10, 8, 2, 0, 8, 2, 0);
        assertEquals(READY, DynastyProcessRefreshReadiness.classify(diagnostics));
    }

    @Test
    void partialWhenAnyProviderRowsRemainUnmapped() {
        var diagnostics = diagnostics(10, 10, 8, 2, 0, 7, 2, 1);
        assertEquals(PARTIAL, DynastyProcessRefreshReadiness.classify(diagnostics));
    }

    @Test
    void partialWhenExactIdentityCrosswalkContainsAmbiguity() {
        var diagnostics = diagnostics(10, 10, 8, 2, 1, 8, 2, 0);
        assertEquals(PARTIAL, DynastyProcessRefreshReadiness.classify(diagnostics));
    }

    @Test
    void blockedWhenNoProviderRowsMap() {
        var diagnostics = diagnostics(10, 10, 8, 2, 0, 0, 0, 10);
        assertEquals(BLOCKED, DynastyProcessRefreshReadiness.classify(diagnostics));
    }

    private static DynastyProcessValueImporter.ProviderDiagnostics diagnostics(
            int valueRows, int playerIdRows, int primaryCrosswalkEntries,
            int uniqueIdentityMappings, int ambiguousIdentityMappings,
            int providerRowsMappedByPrimaryId, int providerRowsMappedByIdentity,
            int providerRowsUnmapped) {
        return new DynastyProcessValueImporter.ProviderDiagnostics(
            valueRows, playerIdRows, primaryCrosswalkEntries,
            uniqueIdentityMappings, ambiguousIdentityMappings,
            providerRowsMappedByPrimaryId, providerRowsMappedByIdentity,
            providerRowsUnmapped);
    }
}
