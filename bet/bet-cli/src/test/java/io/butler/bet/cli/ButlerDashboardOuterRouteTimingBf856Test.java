package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardOuterRouteTimingBf856Test {

    @Test
    void diagnosticsAreStrictlyOptInAndExactRootOnly() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");

        assertTrue(worker.contains(
            "$bf856RouteTimingEnabled = ([string]$env:BUTLER_APP_BF856_ROUTE_TIMING -ceq '1')"));
        assertTrue(worker.contains("if (-not $bf856RouteTimingEnabled -or $RequestTarget -cne '/') { return $null }"));
        assertTrue(worker.contains("X-Butler-BF856-Timing: "));
        assertTrue(worker.contains("$requestTarget -ceq '/'"));
        assertFalse(worker.contains("BUTLER_APP_BF856_ROUTE_TIMING -cne '0'"));
    }

    @Test
    void bf693TimingSeparatesMutexSemaphoreCoreAndCache() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");

        assertTrue(worker.contains("$bf856Timing.mutex_wait_ms = Get-Bf856ElapsedMs"));
        assertTrue(worker.contains("$bf856Timing.semaphore_wait_ms = Get-Bf856ElapsedMs"));
        assertTrue(worker.contains("$bf856Timing.core_proxy_ms = Get-Bf856ElapsedMs"));
        assertTrue(worker.contains("$bf856Timing.singleflight_total_ms = Get-Bf856ElapsedMs"));
        assertTrue(worker.contains("$bf856Timing.cache_hit = 1.0"));
        assertTrue(worker.contains("AddSeconds(5).Ticks"));
        assertTrue(worker.contains("Local\\Butler.Companion.Heavy.{0}"));
        assertTrue(worker.contains("$mutex.WaitOne(180000)"));
    }

    @Test
    void cachedPresentationTransformMeasuresOnlyDiagnosticRequests() throws Exception {
        String wrapper = source("scripts/butler-app-request-worker-cache.ps1");

        assertTrue(wrapper.contains("$bf856PresentationStarted"));
        assertTrue(wrapper.contains("$DiagnosticTimings.presentation_ms = Get-Bf856ElapsedMs"));
        assertTrue(wrapper.contains("$bf856RouteTimingEnabled -and $null -ne $DiagnosticTimings"));
        assertTrue(wrapper.contains("ConvertTo-ButlerUserFacingHtml -Html $Body"));
    }

    @Test
    void focusedRunnerUsesDetachedTempWorktreeAndReadOnlyDashboardGets() throws Exception {
        String script = source("scripts/butler-dashboard-outer-route-timing-diagnostic.ps1");

        assertTrue(script.contains("worktree add --detach"));
        assertTrue(script.contains("Butler-bf856-route-"));
        assertTrue(script.contains("$env:BUTLER_APP_BF856_ROUTE_TIMING = '1'"));
        assertTrue(script.contains("Three simultaneous Dashboard requests after cache expiry"));
        assertTrue(script.contains("Leader misses: {0}; cache followers: {1}"));
        assertTrue(script.contains("expected exactly one concurrent cache miss leader and two single-flight cache followers"));
        assertTrue(script.contains("BF-856 RESULT: COMPLETE"));
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
        throw new IllegalStateException("BF-856 test could not locate " + relativePath);
    }
}
