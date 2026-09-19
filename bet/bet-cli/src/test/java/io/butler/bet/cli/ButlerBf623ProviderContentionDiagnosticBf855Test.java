package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerBf623ProviderContentionDiagnosticBf855Test {

    @Test
    void normalBf623VerificationRemainsOnOriginalSerialDiscoverPath() throws Exception {
        String service = source(
            "bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperPersonalizedTargetService.java");

        assertTrue(service.contains(
            "DiscoveryReport live = discover(bound.sleeperUsername(), bound.sleeperLeagueId());"));
        assertTrue(service.contains(
            "DiscoveryReport live = discover(bound.sleeperUsername(), bound.sleeperLeagueId(), timing);"));
        assertTrue(service.contains("timing.observe(\"user\""));
        assertTrue(service.contains("timing.observe(\"user_leagues\""));
        assertTrue(service.contains("timing.observe(\"league\""));
        assertTrue(service.contains("timing.observe(\"rosters\""));
        assertTrue(service.contains("timing.observe(\"league_users\""));
        assertTrue(service.contains("timing.observe(\"verify_total\""));
    }

    @Test
    void exactWorkerOperationTargetsOnlyTheDiagnosticCli() throws Exception {
        String worker = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerReadOnlyJvmWorker.java");

        assertTrue(worker.contains("case TARGET_VERIFY_DIAGNOSTIC -> executeCapturedWithExitCode"));
        assertTrue(worker.contains("ButlerSleeperPersonalTargetVerificationDiagnosticCli.runEmbedded("));
        assertFalse(worker.contains("TARGET_VERIFY_DIAGNOSTIC -> executeCaptured("));
    }

    @Test
    void diagnosticCliReportsOnlyStageTimingAndReadOnlyBoundary() throws Exception {
        String cli = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerSleeperPersonalTargetVerificationDiagnosticCli.java");

        assertTrue(cli.contains(".verifyBoundTarget(leagueId, stages::put)"));
        for (String stage : new String[] {
            "user",
            "user_leagues",
            "league",
            "rosters",
            "league_users",
            "verify_total"
        }) {
            assertTrue(cli.contains("\"" + stage + "\""));
        }
        assertTrue(cli.contains("BF855_BOUNDARY read_only=true; persistent_jvm=true; refresh=false; sleeper_write=false"));
        assertFalse(cli.contains("bind("));
        assertFalse(cli.contains("compareAndSet"));
    }

    @Test
    void focusedRunnerUsesTwoWorkersAndIsolatedBuildWithoutWrites() throws Exception {
        String script = source("scripts/butler-bf623-provider-contention-diagnostic.ps1");

        assertTrue(script.contains("Butler-bf855-bet-cli-build-"));
        assertTrue(script.contains("-PbutlerIsolatedBuildDir="));
        assertTrue(script.contains("$workers.Add((Start-DiagnosticWorker))"));
        assertTrue(script.contains("for ($round = 1; $round -le 5; $round++)"));
        assertTrue(script.contains("concurrent_worker_samples=10"));
        assertTrue(script.contains("TARGET_VERIFY_DIAGNOSTIC"));
        assertTrue(script.contains("BF-851 companion-heavy capacity=2"));
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
        throw new IllegalStateException("BF-855 test could not locate " + relativePath);
    }
}
