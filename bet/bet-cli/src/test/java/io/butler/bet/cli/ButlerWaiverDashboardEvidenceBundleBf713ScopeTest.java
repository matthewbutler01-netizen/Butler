package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWaiverDashboardEvidenceBundleBf713ScopeTest {

    @Test
    void comparisonAndRosterCanOverlapWithoutRunningSummary() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        var task = (java.util.concurrent.Callable<String>) () -> {
            int now = active.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            started.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("release timed out");
                return "ok";
            } finally {
                active.decrementAndGet();
            }
        };

        Thread caller = new Thread(() -> {
            try {
                ButlerWaiverDashboardEvidenceBundleCli.runConcurrentPair(task, task);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        caller.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertEquals(2, peak.get());
        release.countDown();
        caller.join(Duration.ofSeconds(2).toMillis());
        assertFalse(caller.isAlive());
    }

    @Test
    void dispatcherNeverBundlesTheHomepageSummaryTask() throws Exception {
        String dispatch = source("scripts/butler-direct-java-dispatch.ps1");

        assertFalse(dispatch.contains("$Task -eq ':bet:bet-cli:sleeperLiveWaiverLatestGovernedDecisionSummary'"));
        assertTrue(dispatch.contains("$Task -eq ':bet:bet-cli:sleeperLiveWaiverComparisonBundle'"));
        assertTrue(dispatch.contains("$Task -eq ':bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit'"));
        assertTrue(dispatch.contains("'--waiver-board-context-bundle'"));
    }

    @Test
    void pairModeKeepsOnlyComparisonAndRosterSections() throws Exception {
        String bundle = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerWaiverDashboardEvidenceBundleCli.java");
        int start = bundle.indexOf("private static void runComparisonRosterOnly");
        int end = bundle.indexOf("private static Database initializedDatabase", start);
        assertTrue(start >= 0 && end > start);
        String pairMode = bundle.substring(start, end);

        assertTrue(pairMode.contains("runConcurrentPair"));
        assertTrue(pairMode.contains("SleeperLiveWaiverComparisonExecutionBundle"));
        assertTrue(pairMode.contains("SleeperLiveWaiverTargetRosterContextAudit"));
        assertTrue(pairMode.contains("emit(WAIVER_BOARD, waiverBoard)"));
        assertTrue(pairMode.contains("emit(ROSTER_CONTEXT, rosterContext)"));
        assertFalse(pairMode.contains("SleeperLiveWaiverLatestGovernedDecisionSummary"));
        assertFalse(pairMode.contains("emit(SUMMARY"));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IOException("BF-713 test could not locate " + relativePath);
    }
}
