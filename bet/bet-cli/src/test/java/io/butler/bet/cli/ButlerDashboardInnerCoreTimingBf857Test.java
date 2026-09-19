package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardInnerCoreTimingBf857Test {

    @Test
    void publicWorkerPropagatesOnlyExplicitDiagnosticTiming() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");

        assertTrue(worker.contains(
            "$bf857CoreTimingEnabled = ([string]$env:BUTLER_APP_BF857_CORE_TIMING -ceq '1')"));
        assertTrue(worker.contains("X-Butler-BF857-Timing"));
        assertTrue(worker.contains("$RequestTarget -ceq '/'"));
        assertTrue(worker.contains("Bf857Timing = $bf857Timing"));
        assertFalse(worker.contains("BUTLER_APP_BF857_CORE_TIMING -cne '0'"));
    }

    @Test
    void corePoolMeasuresPreservedBackendRoundtrip() throws Exception {
        String worker = source("scripts/butler-app-core-pool-worker-impl.ps1");

        assertTrue(worker.contains("pool_backend_ms="));
        assertTrue(worker.contains("$response.Headers['X-Butler-BF857-Timing']"));
        assertTrue(worker.contains("-Bf857Timing $proxied.Bf857Timing"));
        assertTrue(worker.contains("$RequestTarget -ceq '/'"));
    }

    @Test
    void stagedTransformMeasuresPreservedDashboardSummaryAndHtml() throws Exception {
        String transform = source("scripts/butler-bf857-inner-core-timing-transform.ps1");

        assertTrue(transform.contains("preserved_dashboard_ms="));
        assertTrue(transform.contains("dashboard_summary_ms"));
        assertTrue(transform.contains("dashboard_html_ms"));
        assertTrue(transform.contains("Invoke-Bf740PersistentCoreWorker -Operation 'LATEST_SUMMARY'"));
        assertTrue(transform.contains("$path -ceq '/'"));
        assertTrue(transform.contains("X-Butler-BF857-Timing"));
    }

    @Test
    void stagingIsStrictlyOptInAndRunsAfterManagerTransforms() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        assertTrue(staging.contains("BUTLER_APP_BF857_CORE_TIMING -ceq '1'"));
        assertTrue(staging.contains("butler-bf857-inner-core-timing-transform.ps1"));
        assertTrue(staging.indexOf("butler-dashboard-bf843-matchup-routing-transform.ps1")
            < staging.indexOf("butler-bf857-inner-core-timing-transform.ps1"));
    }

    @Test
    void focusedRunnerUsesShortTempPathAndReadOnlyRootGets() throws Exception {
        String script = source("scripts/butler-dashboard-inner-core-timing-diagnostic.ps1");

        assertTrue(script.contains("('b857-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))"));
        assertTrue(script.contains("$env:BUTLER_APP_BF856_ROUTE_TIMING = '1'"));
        assertTrue(script.contains("$env:BUTLER_APP_BF857_CORE_TIMING = '1'"));
        assertTrue(script.contains("Warm miss-path p50"));
        assertTrue(script.contains("BF-857 RESULT: COMPLETE"));
        assertTrue(script.contains("rd /s /q"));
        assertFalse(script.contains("$coreSingle = Join-Path"),
            "BF-858 diagnostic must not rewrite core-single before BF-742 exact staging");
        assertFalse(script.contains("Dashboard output task capture"),
            "BF-858 diagnostic must not mutate governed-Dashboard launch contracts");
        assertFalse(script.contains("'/refresh'"));
        assertFalse(script.contains("POST"));
        assertFalse(script.contains("submitTransaction"));
        assertFalse(script.contains("setFaab"));
    }

    private static String source(String relativePath) throws Exception {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("BF-857 test could not locate " + relativePath);
    }
}
