package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperPersonalizedTargetStageDiagnosticBf744Test {
    @Test
    void windowsRunnerUsesConfiguredCanonicalLeagueAndPreparedReadOnlyRuntime() throws Exception {
        String script = source("scripts/butler-target-stage-diagnostic.ps1");
        String service = source("bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperPersonalizedTargetService.java");

        assertTrue(script.contains("app-league.txt"));
        assertTrue(script.contains("[Guid]::TryParse($leagueId, [ref]$parsedLeagueId)"));
        assertTrue(script.contains("$parsedLeagueId.ToString('D').ToLowerInvariant()"));
        assertTrue(script.contains("build\\install\\bet-cli\\lib"));
        assertTrue(script.contains("io.butler.bet.sleeper.SleeperPersonalizedTargetStageDiagnostic"));
        assertTrue(script.contains("===BUTLER_TARGET_STAGE_TIMING:"));
        assertTrue(script.contains("unchanged serial BF-623 verification only"));
        assertTrue(script.contains("/refresh excluded"));
        assertFalse(script.contains("production-refresh"));

        assertTrue(service.contains("public VerifiedTarget verifyBoundTargetSerialDiagnostic("));
        assertTrue(service.contains("return verifyBoundTargetParallel(butlerLeagueId, ProviderStageObserver.NO_OP);"));
        assertTrue(service.contains("Executors.newFixedThreadPool(4)"));
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
        throw new IOException("BF-744 test could not locate " + relativePath);
    }
}
