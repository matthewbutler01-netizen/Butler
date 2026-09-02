package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynastyProcessRefreshGuardTest {
    @Test
    void allowsReadyProviderDiagnostics() {
        var diagnostics = diagnostics(2, 0, 0, 2);
        assertDoesNotThrow(() -> DynastyProcessRefreshGuard.requireReady(diagnostics));
    }

    @Test
    void blocksPartialProviderDiagnostics() {
        var diagnostics = diagnostics(2, 1, 0, 1);
        assertThrows(IllegalArgumentException.class,
            () -> DynastyProcessRefreshGuard.requireReady(diagnostics));
    }

    @Test
    void blocksAmbiguousIdentityDiagnostics() {
        var diagnostics = diagnostics(2, 0, 1, 2);
        assertThrows(IllegalArgumentException.class,
            () -> DynastyProcessRefreshGuard.requireReady(diagnostics));
    }

    @Test
    void blocksZeroMappedProviderDiagnostics() {
        var diagnostics = diagnostics(2, 2, 0, 0);
        assertThrows(IllegalArgumentException.class,
            () -> DynastyProcessRefreshGuard.requireReady(diagnostics));
    }

    private DynastyProcessValueImporter.ProviderDiagnostics diagnostics(
            int valueRows, int unmapped, int ambiguous, int mappedByPrimaryId) {
        return new DynastyProcessValueImporter.ProviderDiagnostics(
            valueRows, 2, 2, 2 - ambiguous, ambiguous,
            mappedByPrimaryId, 0, unmapped);
    }
}
