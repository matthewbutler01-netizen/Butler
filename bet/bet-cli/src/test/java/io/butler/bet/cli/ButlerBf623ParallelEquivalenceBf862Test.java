package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerBf623ParallelEquivalenceBf862Test {

    @Test
    void productionVerificationUsesProvenParallelPathAndSerialReferenceRemainsSeparate() throws Exception {
        String service = source(
            "bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperPersonalizedTargetService.java");

        assertTrue(service.contains(
            "return verifyBoundTargetParallel(butlerLeagueId, ProviderStageObserver.NO_OP);"));
        assertTrue(service.contains(
            "return verifyBoundTargetParallel(butlerLeagueId, observer);"));
        assertTrue(service.contains("verifyBoundTargetSerialDiagnostic("));
        assertTrue(service.contains(
            "DiscoveryReport live = discover(bound.sleeperUsername(), bound.sleeperLeagueId(), timing);"));
        assertTrue(service.contains("verifyBoundTargetParallelDiagnostic("));
        assertTrue(service.contains("Executors.newFixedThreadPool(4)"));
        assertTrue(service.contains("CompletableFuture<TimedPayload>"));
    }

    @Test
    void parallelDiagnosticPreservesAllExactValidationSurfaces() throws Exception {
        String service = source(
            "bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperPersonalizedTargetService.java");

        assertTrue(service.contains("source.user(bound.sleeperUsername())"));
        assertTrue(service.contains("source.userLeagues(user.userId(), TARGET_SEASON)"));
        assertTrue(service.contains("source.league(bound.sleeperLeagueId())"));
        assertTrue(service.contains("source.rosters(bound.sleeperLeagueId())"));
        assertTrue(service.contains("source.users(bound.sleeperLeagueId())"));
        assertTrue(service.contains("selected Sleeper league is not exactly present"));
        assertTrue(service.contains("user-league list and direct league observation disagree"));
        assertTrue(service.contains("current roster memberships instead of exactly one"));
        assertTrue(service.contains("requesting user is absent from selected league users surface"));
        assertTrue(service.contains("VerifiedTarget result = verifyResolvedTarget(leagueId, bound, live)"));
    }

    @Test
    void workerExposesDedicatedParallelDiagnosticOnly() throws Exception {
        String worker = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerReadOnlyJvmWorker.java");

        assertTrue(worker.contains("TARGET_VERIFY_PARALLEL_DIAGNOSTIC"));
        assertTrue(worker.contains("runParallelEmbedded("));
        assertTrue(worker.contains("case TARGET_VERIFY_PARALLEL_DIAGNOSTIC -> executeCapturedWithExitCode"));
        assertFalse(worker.contains("case LATEST_SUMMARY -> executeCapturedWithExitCode(() ->\n                ButlerSleeperPersonalTargetVerificationDiagnosticCli.runParallelEmbedded"));
    }

    @Test
    void runnerRequiresExactTargetEquivalenceAndReadOnlyBoundaries() throws Exception {
        String script = source("scripts/butler-bf623-parallel-equivalence-diagnostic.ps1");

        assertTrue(script.contains("TARGET_VERIFY_DIAGNOSTIC"));
        assertTrue(script.contains("TARGET_VERIFY_PARALLEL_DIAGNOSTIC"));
        assertTrue(script.contains("serial and parallel warm target signatures differ"));
        assertTrue(script.contains("exact target signature drifted"));
        assertTrue(script.contains("target_equivalence=EXACT"));
        assertTrue(script.contains("serial reference retained"));
        assertTrue(script.contains("production uses proven parallel verification"));
        assertTrue(script.contains("parallel_c2_ms"));
        assertTrue(script.contains("BF-862 RESULT: COMPLETE"));
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
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("BF-862 test could not locate " + relativePath);
    }
}
