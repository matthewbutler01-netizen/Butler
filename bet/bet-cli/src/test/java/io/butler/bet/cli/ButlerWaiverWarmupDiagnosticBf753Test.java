package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWaiverWarmupDiagnosticBf753Test {
    @Test
    void diagnosticTemporarilyReplacesOnlyLeagueWarmRouteAndRestoresCoreBytes() throws Exception {
        String source = source("scripts/butler-waiver-warmup-diagnostic.ps1");

        assertTrue(source.contains("$needle = 'http://127.0.0.1:$BackendPort/league'"));
        assertTrue(source.contains("$replacement = 'http://127.0.0.1:$BackendPort/waivers'"));
        assertTrue(source.contains("expected exactly one BF-751 warmup route contract"));
        assertTrue(source.contains("[System.IO.File]::ReadAllBytes($corePath)"));
        assertTrue(source.contains("[System.IO.File]::WriteAllBytes($corePath, $originalCoreBytes)"));
        assertTrue(source.contains("Test-Bf753BytesEqual"));
        assertTrue(source.contains("BF-753 CLEANUP FAILED"));
        assertTrue(source.contains("scripts\\butler-acceptance.cmd"));
        assertFalse(source.contains("worktree"));
        assertFalse(source.contains("BUTLER_APP_CORE_POOL_SIZE"));
    }

    @Test
    void diagnosticRefusesDirtyCoreAndForcesThenRestoresNormalRuntimeDefaults() throws Exception {
        String source = source("scripts/butler-waiver-warmup-diagnostic.ps1");

        assertTrue(source.contains("@('diff', '--quiet', '--', 'scripts/butler-app-shell-core.ps1')"));
        assertTrue(source.contains("already has local changes; refusing temporary diagnostic patch"));
        assertTrue(source.contains("Remove-Item Env:BUTLER_APP_PERSISTENT_CORE_WORKER"));
        assertTrue(source.contains("Remove-Item Env:BUTLER_APP_SLEEPER_TRANSPORT_PREWARM"));
        assertTrue(source.contains("Remove-Item Env:BUTLER_APP_CORE_POOL_WARMUP"));
        assertTrue(source.contains("Restore-Bf753EnvironmentValue -Name 'BUTLER_APP_PERSISTENT_CORE_WORKER'"));
        assertTrue(source.contains("Restore-Bf753EnvironmentValue -Name 'BUTLER_APP_SLEEPER_TRANSPORT_PREWARM'"));
        assertTrue(source.contains("Restore-Bf753EnvironmentValue -Name 'BUTLER_APP_CORE_POOL_WARMUP'"));
    }

    @Test
    void diagnosticBoundaryRemainsReadOnlyAndProductionWarmupIsWaiversAfterPromotion() throws Exception {
        String diagnostic = source("scripts/butler-waiver-warmup-diagnostic.ps1");
        String core = source("scripts/butler-app-shell-core.ps1");

        assertTrue(diagnostic.contains("one sequential best-effort /waivers warm read per core replaces /league only for this run"));
        assertTrue(diagnostic.contains("/refresh excluded"));
        assertTrue(diagnostic.contains("no Butler or Sleeper write path is invoked"));
        assertFalse(diagnostic.contains("/refresh?"));

        assertTrue(core.contains("http://127.0.0.1:$BackendPort/waivers"));
        assertFalse(core.contains("http://127.0.0.1:$BackendPort/league"));
    }

    @Test
    void diagnosticSourceRemainsAsciiOnly() throws Exception {
        byte[] bytes = Files.readAllBytes(locate("scripts/butler-waiver-warmup-diagnostic.ps1"));
        for (byte value : bytes) {
            assertTrue((value & 0xff) <= 0x7f, "BF-753 PowerShell source must remain ASCII-only");
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
        throw new IllegalStateException("BF-753 test could not locate " + relativePath);
    }
}
