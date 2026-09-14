package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerCorePoolWidthDiagnosticBf752Test {
    @Test
    void diagnosticUsesDetachedWorktreeAndLeavesProductionSourceUntouched() throws Exception {
        String source = source("scripts/butler-core-pool-width-diagnostic.ps1");

        assertTrue(source.contains("[ValidateRange(2, 6)]"));
        assertTrue(source.contains("[int]$CoreWorkers = 4"));
        assertTrue(source.contains("@('worktree', 'add', '--detach', $worktreePath, 'HEAD')"));
        assertTrue(source.contains("$worktreeCore = Join-Path $worktreePath 'scripts\\butler-app-shell-core.ps1'"));
        assertTrue(source.contains("$needle = '$maxCoreWorkers = 6'"));
        assertTrue(source.contains("$replacement = '$maxCoreWorkers = ' + $CoreWorkers"));
        assertTrue(source.contains("expected exactly one preserved-core width contract"));
        assertTrue(source.contains("scripts\\butler-acceptance.cmd"));
        assertTrue(source.contains("@('worktree', 'remove', '--force', $worktreePath)"));
        assertTrue(source.contains("@('worktree', 'prune')"));
        assertTrue(source.contains("finally {"));
        assertTrue(source.contains("production checkout is not modified"));
        assertFalse(source.contains("BUTLER_APP_CORE_POOL_SIZE"));
        assertFalse(source.contains("/refresh" + "?"));
    }

    @Test
    void nativeGitAndAcceptanceCommandsUseExitCodesInsteadOfPowerShellStderrPromotion() throws Exception {
        String source = source("scripts/butler-core-pool-width-diagnostic.ps1");

        assertTrue(source.contains("function Invoke-Bf752Git"));
        assertTrue(source.contains("$ErrorActionPreference = 'Continue'"));
        assertTrue(source.contains("$exitCode = $LASTEXITCODE"));
        assertTrue(source.contains("ExitCode = [int]$exitCode"));
        assertTrue(source.contains("$worktreeResult.ExitCode -ne 0"));
        assertTrue(source.contains("$acceptanceExit = $LASTEXITCODE"));
        assertTrue(source.contains("$pruneBefore = Invoke-Bf752Git"));
        assertTrue(source.contains("$pruneAfter = Invoke-Bf752Git"));
        assertFalse(source.contains("& $git worktree add --detach"));
        assertFalse(source.contains("& $git worktree remove --force"));
    }

    @Test
    void diagnosticForcesNormalWorkerTransportAndCoreWarmupDefaultsThenRestoresEnvironment() throws Exception {
        String source = source("scripts/butler-core-pool-width-diagnostic.ps1");

        assertTrue(source.contains("Remove-Item Env:BUTLER_APP_PERSISTENT_CORE_WORKER"));
        assertTrue(source.contains("Remove-Item Env:BUTLER_APP_SLEEPER_TRANSPORT_PREWARM"));
        assertTrue(source.contains("Remove-Item Env:BUTLER_APP_CORE_POOL_WARMUP"));
        assertTrue(source.contains("Restore-Bf752EnvironmentValue -Name 'BUTLER_APP_PERSISTENT_CORE_WORKER'"));
        assertTrue(source.contains("Restore-Bf752EnvironmentValue -Name 'BUTLER_APP_SLEEPER_TRANSPORT_PREWARM'"));
        assertTrue(source.contains("Restore-Bf752EnvironmentValue -Name 'BUTLER_APP_CORE_POOL_WARMUP'"));
        assertTrue(source.contains("BF-742/BF-748/BF-751 defaults enabled"));
        assertTrue(source.contains("no Butler or Sleeper write path is invoked"));
    }

    @Test
    void diagnosticSourceRemainsAsciiOnly() throws Exception {
        byte[] bytes = Files.readAllBytes(locate("scripts/butler-core-pool-width-diagnostic.ps1"));
        for (byte value : bytes) {
            assertTrue((value & 0xff) <= 0x7f, "BF-752 PowerShell source must remain ASCII-only");
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
        throw new IllegalStateException("BF-752 test could not locate " + relativePath);
    }
}
