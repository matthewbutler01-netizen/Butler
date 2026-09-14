package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPersistentWorkerCommandDiagnosticBf749Test {
    @Test
    void diagnosticUsesExactExistingWorkerBoundaryOnly() throws Exception {
        String source = source("scripts/butler-worker-command-diagnostic.ps1");

        assertTrue(source.contains("[ValidateRange(1, 5)]"));
        assertTrue(source.contains("app-league.txt"));
        assertTrue(source.contains("butler-persistent-core-worker.ps1"));
        assertTrue(source.contains("BUTLER_APP_PERSISTENT_CORE_WORKER = '1'"));
        assertTrue(source.contains("Start-Bf740PersistentCoreWorker"));
        assertTrue(source.contains("Stop-Bf740PersistentCoreWorker"));
        assertTrue(source.contains("Invoke-Bf740PersistentCoreWorker"));
        assertTrue(source.contains("-Operation 'TEAM_BUNDLE'"));
        assertTrue(source.contains("-Operation 'WAIVER_DASHBOARD_BUNDLE'"));
        assertTrue(source.contains("-Operation 'LEAGUE_OVERVIEW'"));
        assertTrue(source.contains("prime_ms="));
        assertTrue(source.contains("team_bundle_ms="));
        assertTrue(source.contains("waiver_dashboard_bundle_ms="));
        assertTrue(source.contains("league_overview_ms="));
        assertTrue(source.contains("/refresh excluded"));

        assertFalse(source.contains("LATEST_SUMMARY"));
        assertFalse(source.contains("EXPLANATION_LOOKUP"));
        assertFalse(source.contains("production-refresh"));
        assertFalse(source.contains("Invoke-RestMethod"));
        assertFalse(source.contains("Invoke-WebRequest"));
        assertFalse(source.contains("Start-Process"));
    }

    @Test
    void diagnosticScriptRemainsAsciiForWindowsPowerShell51() throws Exception {
        String source = source("scripts/butler-worker-command-diagnostic.ps1");
        assertTrue(source.chars().allMatch(value -> value >= 0 && value <= 127));
    }

    private static String source(String relativePath) throws Exception {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("BF-749 test could not locate " + relativePath);
    }
}
