package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperClient;

import java.time.Duration;

/**
 * BF-760 aligns the standalone slow-route diagnostic with the BF-748 production worker transport
 * state before measuring the unchanged BF-733/BF-736 evidence pipeline.
 */
public final class ButlerWarmedSlowRouteStageDiagnosticCli {
    private static final Duration TRANSPORT_PREWARM_TIMEOUT = Duration.ofSeconds(2);

    private ButlerWarmedSlowRouteStageDiagnosticCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: ButlerWarmedSlowRouteStageDiagnosticCli <butler-league-id>");
            }
            String leagueId = args[0].trim();

            long prewarmStarted = System.nanoTime();
            boolean warmed = SleeperClient.prewarmSharedTransportBestEffort(TRANSPORT_PREWARM_TIMEOUT);
            long prewarmElapsedMs = elapsedMs(prewarmStarted);
            if (!warmed) {
                throw new IllegalStateException(
                    "BF-760 BLOCKED: same-transport Sleeper prewarm failed; refusing cold slow-route timing evidence");
            }

            System.out.println(prewarmMarker(prewarmElapsedMs));
            System.out.println(ButlerSlowRouteStageDiagnosticCli.timingMarker(
                ButlerSlowRouteStageDiagnosticCli.measure(leagueId)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Error: BF-760 warmed slow-route diagnostic interrupted");
            System.exit(2);
        } catch (Exception e) {
            System.err.println("Error: " + rootMessage(e));
            System.exit(2);
        }
    }

    static String prewarmMarker(long elapsedMs) {
        if (elapsedMs < 0L) throw new IllegalArgumentException("elapsedMs must not be negative");
        return "===BUTLER_SLOW_ROUTE_TRANSPORT_PREWARM:state=SUCCESS;elapsed_ms=" + elapsedMs + "===";
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
