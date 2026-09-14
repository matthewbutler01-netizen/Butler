package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSteadyStateSlowRouteStageDiagnosticBf763Test {
    @Test
    void wrapperRunsOneDiscardedSameJvmPipelineBeforeReportedMeasurement() throws Exception {
        String source = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerWarmedSlowRouteStageDiagnosticCli.java");
        String prewarm = "SleeperClient.prewarmSharedTransportBestEffort(TRANSPORT_PREWARM_TIMEOUT)";
        String measure = "ButlerSlowRouteStageDiagnosticCli.measure(leagueId)";

        assertEquals(2, occurrences(source, measure));
        int prewarmAt = source.indexOf(prewarm);
        int firstMeasureAt = source.indexOf(measure);
        int secondMeasureAt = source.lastIndexOf(measure);
        assertTrue(prewarmAt >= 0);
        assertTrue(prewarmAt < firstMeasureAt);
        assertTrue(firstMeasureAt < secondMeasureAt);
        assertTrue(source.contains("pipelineWarmupMarker(pipelineWarmupElapsedMs)"));
        assertFalse(source.contains("CompletableFuture"));
        assertFalse(source.contains("parallelStream"));
        assertFalse(source.contains("ExecutorService"));
    }

    @Test
    void wrapperExposesStrictPipelineWarmupMarker() {
        assertEquals(
            "===BUTLER_SLOW_ROUTE_PIPELINE_WARMUP:state=SUCCESS;elapsed_ms=73===",
            ButlerWarmedSlowRouteStageDiagnosticCli.pipelineWarmupMarker(73));
    }

    @Test
    void powershellRequiresWarmupBeforeAcceptingSteadyStateTiming() throws Exception {
        String source = source("scripts/butler-slow-route-stage-diagnostic.ps1");

        assertTrue(source.contains("^===BUTLER_SLOW_ROUTE_PIPELINE_WARMUP:.*===$"));
        assertTrue(source.contains(
            "^===BUTLER_SLOW_ROUTE_PIPELINE_WARMUP:state=SUCCESS;elapsed_ms=(?<elapsed>\\d+)===$"));
        assertTrue(source.contains("same-JVM warmup"));
        assertTrue(source.contains("discarded, outside reported timing"));
        assertTrue(source.contains("steady-state after one same-JVM warmup"));
        assertTrue(source.contains("unchanged BF-623 verification still runs on warmup and measured passes"));
        assertTrue(source.contains("no provider payload caching or concurrency"));
        assertTrue(source.contains("/refresh excluded"));
        assertTrue(source.contains("no Butler or Sleeper write path is invoked"));
        assertFalse(source.contains("Start-Job"));
        assertFalse(source.contains("-Parallel"));
    }

    @Test
    void windowsRunnerRemainsAsciiOnly() throws Exception {
        byte[] bytes = Files.readAllBytes(locate("scripts/butler-slow-route-stage-diagnostic.ps1"));
        for (byte value : bytes) {
            assertTrue((value & 0xff) <= 0x7f, "BF-763 PowerShell source must remain ASCII-only");
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) count++;
        return count;
    }

    private static String source(String relativePath) throws Exception {
        return Files.readString(locate(relativePath), StandardCharsets.UTF_8);
    }

    private static Path locate(String relativePath) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException("BF-763 test could not locate " + relativePath);
    }
}
