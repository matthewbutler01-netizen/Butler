package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerMyTeamEvidenceBundleBf716RosterOverlapTest {

    @Test
    void postureUsesRosterFreedWorkerWhileFourIndependentAnalysesRemainActive() throws Exception {
        ExecutorService executor = ButlerMyTeamEvidenceBundleCli.newEvidenceExecutor();
        CountDownLatch rosterStarted = new CountDownLatch(1);
        CountDownLatch independentStarted = new CountDownLatch(4);
        CountDownLatch releaseRoster = new CountDownLatch(1);
        CountDownLatch releaseIndependent = new CountDownLatch(1);
        CountDownLatch postureStarted = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Future<Integer>> independent = new ArrayList<>();

        try {
            Future<Integer> roster = ButlerMyTeamEvidenceBundleCli.submitEvidence(executor, () -> {
                int now = active.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                rosterStarted.countDown();
                try {
                    if (!releaseRoster.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("BF-716 test timed out waiting to release roster analysis.");
                    }
                    return 2026;
                } finally {
                    active.decrementAndGet();
                }
            });

            for (int index = 0; index < 4; index++) {
                int value = index;
                independent.add(ButlerMyTeamEvidenceBundleCli.submitEvidence(executor, () -> {
                    int now = active.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    independentStarted.countDown();
                    try {
                        if (!releaseIndependent.await(2, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("BF-716 test timed out waiting to release independent analysis.");
                        }
                        return value;
                    } finally {
                        active.decrementAndGet();
                    }
                }));
            }

            assertTrue(rosterStarted.await(2, TimeUnit.SECONDS));
            assertTrue(independentStarted.await(2, TimeUnit.SECONDS));
            assertEquals(5, peak.get(), "roster plus four independent analyses should fill exactly five worker slots");

            releaseRoster.countDown();
            int season = ButlerMyTeamEvidenceBundleCli.await(roster);
            Future<Integer> posture = ButlerMyTeamEvidenceBundleCli.submitEvidence(executor, () -> {
                int now = active.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                postureStarted.countDown();
                try {
                    return season;
                } finally {
                    active.decrementAndGet();
                }
            });

            assertTrue(postureStarted.await(2, TimeUnit.SECONDS),
                "posture should start in the roster-freed slot while four independent analyses remain blocked");
            assertEquals(2026, ButlerMyTeamEvidenceBundleCli.await(posture));
            assertEquals(5, peak.get(), "BF-716 must not raise the BF-711 five-worker concurrency ceiling");

            releaseIndependent.countDown();
            for (int index = 0; index < independent.size(); index++) {
                assertEquals(index, ButlerMyTeamEvidenceBundleCli.await(independent.get(index)));
            }
        } finally {
            releaseRoster.countDown();
            releaseIndependent.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void productionVerifiesTargetOnceAndUsesRosterReportSeasonWithoutTextReparse() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerMyTeamEvidenceBundleCli.java");

        assertEquals(1, occurrences(source, "ButlerPersonalizedTargetCliSupport.verify(database, leagueId)"));
        assertEquals(1, occurrences(source, "new SleeperLiveWaiverTargetRosterContextAudit(database).audit(leagueId, target.sleeperUserId())"));
        assertTrue(source.contains("int season = rosterContextReport.providerSeason();"));
        assertTrue(source.contains("ButlerPersonalizedTargetCliSupport.printVerified(target);"));
        assertTrue(source.contains("ButlerSleeperLiveWaiverTargetRosterContextAuditCli.print(rosterContextReport);"));
        assertTrue(source.contains("Executors.newFixedThreadPool(POST_ROSTER_WORKERS)"));
        assertTrue(source.contains("static final int POST_ROSTER_WORKERS = 5;"));
        assertTrue(!source.contains("Pattern.compile"));
        assertTrue(!source.contains("providerSeason(rosterContext)"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) count++;
        return count;
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
        throw new IOException("BF-716 test could not locate " + relativePath);
    }
}
