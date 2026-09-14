package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTeamSecondWarmupDiagnosticBf755Test {
    @Test
    void diagnosticPreservesWaiverWarmupAndInjectsOneSequentialTeamWarmup() throws Exception {
        String source = source("scripts/butler-team-second-warmup-diagnostic.ps1");

        assertTrue(source.contains("$productionWaiverTarget = 'http://127.0.0.1:$BackendPort/waivers'"));
        assertTrue(source.contains("http://127.0.0.1:$BackendPort/team"));
        assertTrue(source.contains("Invoke-Bf755PreservedCoreTeamWarmup -BackendPort `$backendPort"));
        assertTrue(source.contains("$request.Timeout = 3000"));
        assertTrue(source.contains("$request.ReadWriteTimeout = 3000"));
        assertTrue(source.contains("$request.Proxy = $null"));
        assertTrue(source.contains("$request.KeepAlive = $false"));
        assertTrue(source.contains("BF-755 preserved-core team warmup skipped"));
        assertFalse(source.contains("Start-Job"));
        assertFalse(source.contains("-Parallel"));
        assertFalse(source.contains("worktree"));
    }

    @Test
    void diagnosticRefusesDirtyCoreAndRestoresExactBytesAndEnvironment() throws Exception {
        String source = source("scripts/butler-team-second-warmup-diagnostic.ps1");

        assertTrue(source.contains("@('diff', '--quiet', '--', 'scripts/butler-app-shell-core.ps1')"));
        assertTrue(source.contains("already has local changes; refusing temporary diagnostic patch"));
        assertTrue(source.contains("[System.IO.File]::ReadAllBytes($corePath)"));
        assertTrue(source.contains("[System.IO.File]::WriteAllBytes($corePath, $originalCoreBytes)"));
        assertTrue(source.contains("Test-Bf755BytesEqual"));
        assertTrue(source.contains("BF-755 CLEANUP FAILED"));
        assertTrue(source.contains("Restore-Bf755EnvironmentValue -Name 'BUTLER_APP_PERSISTENT_CORE_WORKER'"));
        assertTrue(source.contains("Restore-Bf755EnvironmentValue -Name 'BUTLER_APP_SLEEPER_TRANSPORT_PREWARM'"));
        assertTrue(source.contains("Restore-Bf755EnvironmentValue -Name 'BUTLER_APP_CORE_POOL_WARMUP'"));
    }

    @Test
    void productionWarmupNowIncludesWaiverThenTeamAfterBf756Promotion() throws Exception {
        String core = source("scripts/butler-app-shell-core.ps1");
        String warmup = between(core, "function Invoke-PreservedCoreWarmup {", "function Stop-OwnedProcessTree {");

        String waiverUrl = "http://127.0.0.1:$BackendPort/waivers";
        String teamUrl = "http://127.0.0.1:$BackendPort/team";
        assertTrue(warmup.contains(waiverUrl));
        assertTrue(warmup.contains(teamUrl));
        assertTrue(warmup.indexOf(waiverUrl) < warmup.indexOf(teamUrl));
        assertFalse(warmup.contains("/refresh"));

        String diagnostic = source("scripts/butler-team-second-warmup-diagnostic.ps1");
        assertTrue(diagnostic.contains("production /waivers warmup preserved; one additional sequential best-effort /team warm read per core"));
        assertTrue(diagnostic.contains("/refresh excluded"));
        assertTrue(diagnostic.contains("no Butler or Sleeper write path is invoked"));
    }

    @Test
    void diagnosticSourceRemainsAsciiOnly() throws Exception {
        byte[] bytes = Files.readAllBytes(locate("scripts/butler-team-second-warmup-diagnostic.ps1"));
        for (byte value : bytes) {
            assertTrue((value & 0xff) <= 0x7f, "BF-755 PowerShell source must remain ASCII-only");
        }
    }

    private static String between(String source, String begin, String end) {
        int start = source.indexOf(begin);
        int finish = source.indexOf(end, start + begin.length());
        if (start < 0 || finish < 0 || finish <= start) {
            throw new IllegalStateException("BF-755 test could not locate source boundaries");
        }
        return source.substring(start, finish);
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
        throw new IllegalStateException("BF-755 test could not locate " + relativePath);
    }
}
