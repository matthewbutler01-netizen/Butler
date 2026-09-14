package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerReadOnlyJvmWorkerBf748Test {
    private static final String LEAGUE_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    @Test
    void helpAndRejectedFramesDoNotWarmAndFirstRealReadWarmsExactlyOnce() throws Exception {
        String input = String.join("\n",
            "HELP\thelp-1",
            "NOT_ALLOWED\tbad-1\t" + LEAGUE_ID,
            "TEAM_BUNDLE\tteam-1\t" + LEAGUE_ID,
            "LEAGUE_OVERVIEW\tleague-1\t" + LEAGUE_ID,
            "QUIT") + "\n";
        StringWriter output = new StringWriter();
        AtomicInteger warmups = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();

        ButlerReadOnlyJvmWorker.serve(
            new BufferedReader(new StringReader(input)),
            new PrintWriter(output, true),
            request -> {
                executions.incrementAndGet();
                return new ButlerReadOnlyJvmWorker.Execution(0, request.operation().name(), "");
            },
            warmups::incrementAndGet);

        assertEquals(1, warmups.get());
        assertEquals(3, executions.get());
        String protocol = output.toString();
        assertTrue(protocol.startsWith(ButlerReadOnlyJvmWorker.READY + System.lineSeparator()));
        assertTrue(protocol.contains("REJECT\t"));
        assertTrue(protocol.endsWith(ButlerReadOnlyJvmWorker.BYE + System.lineSeparator()));
    }

    @Test
    void helpOnlyWorkerRemainsProviderWarmupFree() throws Exception {
        String input = "HELP\thelp-1\nQUIT\n";
        StringWriter output = new StringWriter();
        AtomicInteger warmups = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();

        ButlerReadOnlyJvmWorker.serve(
            new BufferedReader(new StringReader(input)),
            new PrintWriter(output, true),
            request -> {
                executions.incrementAndGet();
                return new ButlerReadOnlyJvmWorker.Execution(0, "help", "");
            },
            warmups::incrementAndGet);

        assertEquals(0, warmups.get());
        assertEquals(1, executions.get());
    }

    @Test
    void emergencyOptOutIsExactZeroOnly() {
        assertFalse(ButlerReadOnlyJvmWorker.sleeperTransportPrewarmEnabled("0"));
        assertTrue(ButlerReadOnlyJvmWorker.sleeperTransportPrewarmEnabled(null));
        assertTrue(ButlerReadOnlyJvmWorker.sleeperTransportPrewarmEnabled(""));
        assertTrue(ButlerReadOnlyJvmWorker.sleeperTransportPrewarmEnabled("false"));
        assertTrue(ButlerReadOnlyJvmWorker.sleeperTransportPrewarmEnabled("00"));
    }

    @Test
    void sourcePinsBoundedBestEffortProductionWarmupWithoutChangingAllowlist() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerReadOnlyJvmWorker.java");

        assertTrue(source.contains("BUTLER_APP_SLEEPER_TRANSPORT_PREWARM"));
        assertTrue(source.contains("Duration.ofSeconds(2)"));
        assertTrue(source.contains("request.operation() != Operation.HELP"));
        assertTrue(source.contains("SleeperClient.prewarmSharedTransportBestEffort"));
        assertTrue(source.contains("case HELP ->"));
        assertTrue(source.contains("case LEAGUE_OVERVIEW ->"));
        assertTrue(source.contains("case TEAM_BUNDLE ->"));
        assertTrue(source.contains("case LATEST_SUMMARY ->"));
        assertTrue(source.contains("case WAIVER_DASHBOARD_BUNDLE ->"));
        assertTrue(source.contains("case EXPLANATION_LOOKUP ->"));
        assertFalse(source.contains("/refresh"));
        assertFalse(source.contains("production-refresh"));
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
        throw new IllegalStateException("BF-748 test could not locate " + relativePath);
    }
}
