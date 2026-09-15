package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPackagedCompanionRuntimeBf789Test {

    @Test
    void companionProxyBindsToPackagedPrebuiltRuntime() throws Exception {
        String proxy = source("scripts/butler-companion-read-proxy.cmd");

        assertTrue(proxy.contains("BUTLER_APP_RUNTIME_LIB"));
        assertTrue(proxy.contains("bet\\bet-cli\\build\\install\\bet-cli\\lib"));
        assertTrue(proxy.contains("butler-direct-java-dispatch.ps1"));
        assertFalse(proxy.contains(":bet:bet-cli:installDist"));
    }

    @Test
    void directJavaDispatcherAuthorizesHistoryWithoutWideningArbitraryTasks() throws Exception {
        String dispatcher = source("scripts/butler-direct-java-dispatch.ps1");

        assertTrue(dispatcher.contains(":bet:bet-cli:sleeperLiveWaiverRecommendationAuditHistory"));
        assertTrue(dispatcher.contains("io.butler.bet.cli.ButlerSleeperLiveWaiverRecommendationAuditHistoryCli"));
        assertTrue(dispatcher.contains("interactive Gradle task is not authorized for direct Java execution"));
        assertTrue(dispatcher.contains("BUTLER_APP_DATA_DIR is required for Butler Java execution"));
    }

    @Test
    void publicRequestWorkersRouteCompanionReadsAwayFromReleaseGradleShim() throws Exception {
        String cache = source("scripts/butler-app-request-worker-cache.ps1");

        assertTrue(cache.contains("$gradle = Join-Path $RepoRoot 'scripts\\butler-companion-read-proxy.cmd'"));
        assertTrue(cache.contains("BF-789 BLOCKED: companion read proxy is unavailable"));
        assertTrue(cache.contains("The release Gradle shim remains fail-closed"));
    }

    @Test
    void packagedAcceptanceFullyLoadsTradeAndHistoryAndRechecksHealth() throws Exception {
        String acceptance = source("scripts/butler-packaged-companion-route-acceptance.ps1");
        String releaseGate = source("scripts/butler-release-acceptance.cmd");

        assertTrue(acceptance.contains("'/trade?load=1'"));
        assertTrue(acceptance.contains("'/history?load=1'"));
        assertTrue(acceptance.contains("'Evaluate a trade'"));
        assertTrue(acceptance.contains("'Immutable governed waiver audits'"));
        assertTrue(acceptance.contains("post-{0} health"));
        assertTrue(acceptance.contains("BF-789 PACKAGED COMPANION ROUTES: PASS"));
        assertTrue(acceptance.contains("GET_ONLY; /refresh EXCLUDED; NO_BUTLER_OR_SLEEPER_TRANSACTION_WRITE"));
        assertFalse(acceptance.contains("Method = 'POST'"));

        assertTrue(releaseGate.contains("butler-packaged-companion-route-acceptance.ps1"));
        assertTrue(releaseGate.contains("BF-789 PACKAGED COMPANION ROUTES: PASS"));
    }

    @Test
    void runtimeBundleKeepsGradleFailClosed() throws Exception {
        String builder = source("scripts/butler-runtime-release-bundle.ps1");

        assertTrue(builder.contains("runtime package Gradle shim only authorizes the prebuilt installDist startup probe"));
        assertTrue(builder.contains("exit /b 77"));
        assertTrue(builder.contains("refresh_toolchain=BLOCKED_FAIL_CLOSED_IN_RUNTIME_PACKAGE"));
        assertFalse(builder.contains("butler-companion-read-proxy.cmd') + ' goto"));
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
        throw new IOException("BF-789 test could not locate " + relativePath);
    }
}
