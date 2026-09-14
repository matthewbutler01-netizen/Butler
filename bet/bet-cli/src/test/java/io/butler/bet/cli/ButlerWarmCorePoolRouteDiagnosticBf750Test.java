package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWarmCorePoolRouteDiagnosticBf750Test {
    @Test
    void diagnosticUsesProductionShellTwoPrimesGetOnlyCacheBypassAndOwnedCleanup() throws Exception {
        String source = source("scripts/butler-warm-core-pool-route-diagnostic.ps1");

        assertTrue(source.contains("[ValidateRange(1, 5)]"));
        assertTrue(source.contains("butler-app-shell.ps1"));
        assertTrue(source.contains("BUTLER_APP_PERSISTENT_CORE_WORKER"));
        assertTrue(source.contains("BUTLER_APP_SLEEPER_TRANSPORT_PREWARM"));
        assertTrue(source.contains("$request.Method = 'GET'"));
        assertFalse(source.contains("$request.Method = 'POST'"));
        assertTrue(source.contains("/league?bf750=prime1-"));
        assertTrue(source.contains("/league?bf750=prime2-"));
        assertTrue(source.contains("/team?bf750=team-"));
        assertTrue(source.contains("/waivers?bf750=waivers-"));
        assertTrue(source.contains("/league?bf750=league-"));
        assertTrue(source.contains("BF-750 warm core-pool route timing:"));
        assertTrue(source.contains("prime1_ms="));
        assertTrue(source.contains("prime2_ms="));
        assertTrue(source.contains("team_ms="));
        assertTrue(source.contains("waivers_ms="));
        assertTrue(source.contains("league_ms="));
        assertTrue(source.contains("& $taskkill /PID $process.Id /T /F"));
        assertTrue(source.contains("finally {"));
        assertFalse(source.contains("-RequestTarget '/refresh'"));
        assertFalse(source.contains("-RequestTarget \"/refresh"));
    }

    @Test
    void existingShellContractsPinQueryBypassAndTwoCoreAlternationPremise() throws Exception {
        String requestWorker = source("scripts/butler-app-request-worker.ps1");
        String corePool = source("scripts/butler-app-shell-core.ps1");

        assertTrue(requestWorker.contains("$path = $requestTarget.Split('?')[0]"));
        assertTrue(requestWorker.contains("$requestTarget -ceq '/team'"));
        assertTrue(requestWorker.contains("$requestTarget -ceq '/' -or $requestTarget -ceq '/waivers' -or $requestTarget -ceq '/league'"));

        String choose = "$backendPort = Get-FreeBackendPort";
        String accept = "$client = $listener.AcceptTcpClient()";
        int chooseIndex = corePool.indexOf(choose);
        int acceptIndex = corePool.indexOf(accept, chooseIndex);
        assertTrue(chooseIndex >= 0, "BF-750 core-pool backend selection contract is missing");
        assertTrue(acceptIndex > chooseIndex,
            "BF-750 requires backend selection before blocking accept, which causes sequential two-core alternation");
        assertTrue(corePool.contains("$busyPorts = @($activeRequests | ForEach-Object { [int]$_.BackendPort })"));
        assertTrue(corePool.contains("if ($busyPorts -notcontains ([int]$candidate)) { return [int]$candidate }"));
    }

    @Test
    void diagnosticSourceRemainsAsciiOnly() throws Exception {
        byte[] bytes = source("scripts/butler-warm-core-pool-route-diagnostic.ps1").getBytes(StandardCharsets.UTF_8);
        for (byte value : bytes) {
            assertTrue((value & 0xff) <= 0x7f, "BF-750 PowerShell source must remain ASCII-only");
        }
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
        throw new IllegalStateException("BF-750 test could not locate " + relativePath);
    }
}
