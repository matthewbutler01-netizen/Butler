package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerCorePoolWidthDiagnosticBf752Test {
    @Test
    void diagnosticTemporarilyPatchesRealCoreThenRestoresExactOriginalBytes() throws Exception {
        String source = source("scripts/butler-core-pool-width-diagnostic.ps1");

        assertTrue(source.contains("[ValidateRange(2, 6)]"));
        assertTrue(source.contains("[int]$CoreWorkers = 4"));
        assertTrue(source.contains("$corePath = Join-Path $repoRoot 'scripts\\butler-app-shell-core.ps1'"));
        assertTrue(source.contains("git @Arguments"));
        assertTrue(source.contains("@('diff', '--quiet', '--', 'scripts/butler-app-shell-core.ps1')"));
        assertTrue(source.contains("already has local changes; refusing temporary diagnostic patch"));
        assertTrue(source.contains("$originalCoreBytes = [System.IO.File]::ReadAllBytes($corePath)"));
        assertTrue(source.contains("$needle = '$maxCoreWorkers = 6'"));
        assertTrue(source.contains("$replacement = '$maxCoreWorkers = ' + $CoreWorkers"));
        assertTrue(source.contains("expected exactly one preserved-core width contract"));
        assertTrue(source.contains("[System.IO.File]::WriteAllBytes($corePath, $originalCoreBytes)"));
        assertTrue(source.contains("Test-Bf752BytesEqual -Left $originalCoreBytes -Right $restored"));
        assertTrue(source.contains("BF-752 CLEANUP FAILED"));
        assertTrue(source.contains("exact original bytes are restored in finally"));
        assertFalse(source.contains("worktree add"));
        assertFalse(source.contains("BUTLER_APP_CORE_POOL_SIZE"));
        assertFalse(source.contains("/refresh" + "?"));
    }

    @Test
    void nativeGitStatusCheckDoesNotPromoteStderrAndExitCodeRemainsAuthoritative() throws Exception {
        String source = source("scripts/butler-core-pool-width-diagnostic.ps1");

        assertTrue(source.contains("function Invoke-Bf752Git"));
        assertTrue(source.contains("$ErrorActionPreference = 'Continue'"));
        assertTrue(source.contains("$exitCode = $LASTEXITCODE"));
        assertTrue(source.contains("$ErrorActionPreference = $previousPreference"));
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
