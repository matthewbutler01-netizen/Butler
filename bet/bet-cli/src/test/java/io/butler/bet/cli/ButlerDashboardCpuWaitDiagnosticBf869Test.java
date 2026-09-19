package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardCpuWaitDiagnosticBf869Test {

    @Test
    void transformAddsOnlyDashboardProcessIdentityToExistingWallTiming() throws Exception {
        String transform = source("scripts/butler-bf857-inner-core-timing-transform.ps1");

        assertTrue(transform.contains("dashboard_process_id = [double]$PID"));
        assertTrue(transform.contains("'dashboard_process_id'"));

        assertTrue(transform.contains("dashboard_summary_ms"));
        assertTrue(transform.contains("dashboard_parse_base_ms"));
        assertTrue(transform.contains("dashboard_snapshot_ms"));
        assertTrue(transform.contains("dashboard_priority_ms"));
        assertTrue(transform.contains("dashboard_decision_ms"));
        assertTrue(transform.contains("dashboard_manager_ms"));
        assertTrue(transform.contains("dashboard_materialize_ms"));

        assertFalse(transform.contains("Get-Bf869ProcessCpuMs"));
        assertFalse(transform.contains("_cpu_ms"));
        assertFalse(transform.contains("TotalProcessorTime"));
    }

    @Test
    void poolTimingExposesSelectedBackendOnlyInsideDiagnosticHeader() throws Exception {
        String pool = source("scripts/butler-app-core-pool-worker-impl.ps1");

        assertTrue(pool.contains("pool_backend_port="));
        assertTrue(pool.contains("pool_backend_ms="));
        assertTrue(pool.contains("$bf857CoreTimingEnabled"));
        assertTrue(pool.contains("$RequestTarget -ceq '/'"));
    }

    @Test
    void runnerRequiresIdentityAndReportsGroupedBackendDistributions() throws Exception {
        String runner = source("scripts/butler-dashboard-inner-core-timing-diagnostic.ps1");

        assertTrue(runner.contains("'pool_backend_port'"));
        assertTrue(runner.contains("'dashboard_process_id'"));
        assertTrue(runner.contains("identity backend="));
        assertTrue(runner.contains("function Write-Bf869BackendGroup"));
        assertTrue(runner.contains("Backend/PID grouped variance (p50 / p90 / max)"));
        assertTrue(runner.contains("route p50/p90/max"));
        assertTrue(runner.contains("html p50/p90/max"));
        assertTrue(runner.contains("BF869_BACKENDS="));
        assertTrue(runner.contains("BF869_DASHBOARD_PIDS="));
        assertTrue(runner.contains("BF869_GROUP_COUNT="));
        assertTrue(runner.contains("BF-869 RESULT: COMPLETE"));

        assertFalse(runner.contains("cpu/wall summary="));
        assertFalse(runner.contains("cpu p50"));
        assertFalse(runner.contains("cpu p90/max"));
        assertFalse(runner.contains("DashboardSummaryCpuMs"));
    }

    @Test
    void diagnosticKeepsBf868SampleControlAndReadOnlyBoundary() throws Exception {
        String runner = source("scripts/butler-dashboard-inner-core-timing-diagnostic.ps1");
        String transform = source("scripts/butler-bf857-inner-core-timing-transform.ps1");

        assertTrue(runner.contains("[ValidateRange(5, 15)]"));
        assertTrue(runner.contains("[int]$SampleCount = 7"));
        assertTrue(runner.contains("for ($i = 1; $i -le $SampleCount; $i++)"));
        assertTrue(runner.contains("Start-Sleep -Seconds 6"));
        assertTrue(runner.contains("$env:BUTLER_APP_BF857_CORE_TIMING = '1'"));
        assertTrue(transform.contains("BUTLER_APP_BF857_CORE_TIMING -cne '1'"));
        assertTrue(runner.contains("BF-856/BF-857/BF-859/BF-860/BF-868/BF-869 timing is diagnostic-only"));

        assertFalse(runner.contains("'/refresh'"));
        assertFalse(runner.contains("Method = 'POST'"));
        assertFalse(runner.contains("submitTransaction"));
        assertFalse(runner.contains("setFaab"));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-869 test could not locate " + relativePath);
    }
}
