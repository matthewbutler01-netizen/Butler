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

class ButlerMyTeamEvidenceBundleBf711ParallelTest {

    @Test
    void postRosterExecutorRunsExactlyFiveIndependentTasksConcurrently() throws Exception {
        ExecutorService executor = ButlerMyTeamEvidenceBundleCli.newEvidenceExecutor();
        CountDownLatch entered = new CountDownLatch(ButlerMyTeamEvidenceBundleCli.POST_ROSTER_WORKERS);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Future<Integer>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < ButlerMyTeamEvidenceBundleCli.POST_ROSTER_WORKERS; index++) {
                int value = index;
                futures.add(ButlerMyTeamEvidenceBundleCli.submitEvidence(executor, () -> {
                    int now = active.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    entered.countDown();
                    try {
                        if (!release.await(2, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("BF-711 test timed out waiting to release parallel evidence tasks.");
                        }
                        return value;
                    } finally {
                        active.decrementAndGet();
                    }
                }));
            }

            assertTrue(entered.await(2, TimeUnit.SECONDS), "all five post-roster tasks should overlap");
            assertEquals(ButlerMyTeamEvidenceBundleCli.POST_ROSTER_WORKERS, peak.get());
            release.countDown();
            for (int index = 0; index < futures.size(); index++) {
                assertEquals(index, ButlerMyTeamEvidenceBundleCli.await(futures.get(index)));
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void productionSubmitsAllAnalysesBeforeAwaitingAndRendersInStableOrder() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerMyTeamEvidenceBundleCli.java");

        int roster = source.indexOf("String rosterContext = capture(() -> printRosterContext(database, leagueId));");
        int season = source.indexOf("int season = providerSeason(rosterContext);", roster);
        int executor = source.indexOf("ExecutorService executor = newEvidenceExecutor();", season);
        int teamSubmit = source.indexOf("teamContextFuture = submitEvidence", executor);
        int rosterSubmit = source.indexOf("rosterStrengthFuture = submitEvidence", teamSubmit);
        int pressureSubmit = source.indexOf("positionalPressureFuture = submitEvidence", rosterSubmit);
        int postureSubmit = source.indexOf("teamPostureFuture = submitEvidence", pressureSubmit);
        int capitalSubmit = source.indexOf("futureCapitalFuture = submitEvidence", postureSubmit);
        int firstAwait = source.indexOf("await(teamContextFuture)", capitalSubmit);
        int firstCapture = source.indexOf("String teamContext = capture", firstAwait);

        assertTrue(roster >= 0 && season > roster && executor > season);
        assertTrue(teamSubmit > executor && rosterSubmit > teamSubmit && pressureSubmit > rosterSubmit);
        assertTrue(postureSubmit > pressureSubmit && capitalSubmit > postureSubmit && firstAwait > capitalSubmit);
        assertTrue(firstCapture > firstAwait, "rendering must remain outside worker threads because capture swaps global System.out");

        int emitRoster = source.indexOf("emit(ROSTER_CONTEXT, rosterContext);", firstCapture);
        int emitTeam = source.indexOf("emit(TEAM_CONTEXT, teamContext);", emitRoster);
        int emitStrength = source.indexOf("emit(ROSTER_STRENGTH, rosterStrength);", emitTeam);
        int emitPressure = source.indexOf("emit(POSITIONAL_PRESSURE, positionalPressure);", emitStrength);
        int emitPosture = source.indexOf("emit(TEAM_POSTURE, teamPosture);", emitPressure);
        int emitCapital = source.indexOf("emit(FUTURE_CAPITAL, futureCapital);", emitPosture);

        assertTrue(emitRoster > firstCapture && emitTeam > emitRoster && emitStrength > emitTeam);
        assertTrue(emitPressure > emitStrength && emitPosture > emitPressure && emitCapital > emitPosture);
        assertTrue(source.contains("executor.shutdownNow();"));
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
        throw new IOException("BF-711 test could not locate " + relativePath);
    }
}
