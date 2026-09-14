package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWarmedSlowRouteStageDiagnosticBf760Test {
    @Test
    void wrapperPrewarmsSharedSleeperTransportBeforeUnchangedSlowRouteMeasurement() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerWarmedSlowRouteStageDiagnosticCli.java");
        String worker = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerReadOnlyJvmWorker.java");

        String prewarm = "SleeperClient.prewarmSharedTransportBestEffort(TRANSPORT_PREWARM_TIMEOUT)";
        String measure = "ButlerSlowRouteStageDiagnosticCli.measure(leagueId)";
        assertTrue(source.contains("Duration.ofSeconds(2)"));
        assertTrue(worker.contains("SLEEPER_TRANSPORT_PREWARM_TIMEOUT = Duration.ofSeconds(2)"));
        assertTrue(source.contains(prewarm));
        assertTrue(source.contains(measure));
        assertTrue(source.indexOf(prewarm) < source.indexOf(measure));
        assertTrue(source.contains("refusing cold slow-route timing evidence"));
        assertFalse(source.contains("CompletableFuture"));
        assertFalse(source.contains("parallelStream"));
        assertFalse(source.contains("ExecutorService"));
    }

    @Test
    void wrapperUsesSeparatePrewarmMarkerAndPreservesLegacyTimingFormatter() {
        assertEquals(
            "===BUTLER_SLOW_ROUTE_TRANSPORT_PREWARM:state=SUCCESS;elapsed_ms=42===",
            ButlerWarmedSlowRouteStageDiagnosticCli.prewarmMarker(42));
    }

    @Test
    void powershellRunnerRequiresPrewarmSuccessAndUnchangedSlowRouteMarker() throws Exception {
        String source = source("scripts/butler-slow-route-stage-diagnostic.ps1");

        assertTrue(source.contains("io.butler.bet.cli.ButlerWarmedSlowRouteStageDiagnosticCli"));
        assertTrue(source.contains("^===BUTLER_SLOW_ROUTE_TRANSPORT_PREWARM:.*===$"));
        assertTrue(source.contains("state=SUCCESS;elapsed_ms=(?<elapsed>\\d+)"));
        assertTrue(source.contains("^===BUTLER_SLOW_ROUTE_TIMING:.*===$"));
        assertTrue(source.contains("warmed-transport diagnostic"));
        assertTrue(source.contains("outside target_ms"));
        assertTrue(source.contains("response discarded"));
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
            assertTrue((value & 0xff) <= 0x7f, "BF-760 PowerShell source must remain ASCII-only");
        }
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
        throw new IllegalStateException("BF-760 test could not locate " + relativePath);
    }
}
