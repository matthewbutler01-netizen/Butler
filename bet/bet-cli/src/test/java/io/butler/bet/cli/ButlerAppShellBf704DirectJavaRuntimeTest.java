package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf704DirectJavaRuntimeTest {

    @Test
    void startupPreparesInstallDistributionAndStagesCompatibilityRuntimeBeforeWorkers() throws Exception {
        String core = source("scripts/butler-app-shell-core.ps1");

        assertTrue(core.contains(":bet:bet-cli:installDist"));
        assertTrue(core.contains("$runtimeLibDir = Join-Path $runtimeInstallDir 'lib'"));
        assertTrue(core.contains("$runtimeRoot = Join-Path $localAppData (\"Butler\\app-runtime-{0}\" -f $PID)"));
        assertTrue(core.contains("Copy-Item -LiteralPath $coreSingleSource -Destination $runtimeCoreSingle -Force"));
        assertTrue(core.contains("Copy-Item -LiteralPath $dashboardSource"));
        assertTrue(core.contains("Copy-Item -LiteralPath $directDispatchSource"));
        assertTrue(core.contains("Copy-Item -LiteralPath $directProxySource -Destination (Join-Path $runtimeRoot 'gradlew.bat') -Force"));
        assertTrue(core.contains("$env:BUTLER_APP_RUNTIME_LIB = $runtimeLibDir"));
        assertTrue(core.contains("$env:BUTLER_APP_REPO_ROOT = $repoRoot"));
        assertTrue(core.contains("-File `\"$runtimeCoreSingle`\""));

        int warm = core.indexOf("    Initialize-ReadOnlyCliRuntime");
        int stage = core.indexOf("    Initialize-DirectJavaRuntime", warm);
        int worker = core.indexOf("$process = Start-PreservedCore -BackendPort $backendPort", stage);
        assertTrue(warm >= 0 && stage > warm && worker > stage);
    }

    @Test
    void compatibilityProxyRoutesOnlyGovernedInteractiveReadTasksToWhitelistedJavaMains() throws Exception {
        String proxy = source("scripts/butler-direct-java-gradle-proxy.cmd");
        String dispatch = source("scripts/butler-direct-java-dispatch.ps1");

        assertTrue(proxy.contains("butler-direct-java-dispatch.ps1"));
        assertTrue(proxy.contains("-Task \"%~1\" -ArgumentText \"%~2\""));

        assertTrue(dispatch.contains("':bet:bet-cli:run' { 'io.butler.bet.cli.ButlerCommandRouter'"));
        assertTrue(dispatch.contains("ButlerSleeperLiveWaiverTargetRosterContextAuditCli"));
        assertTrue(dispatch.contains("ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli"));
        assertTrue(dispatch.contains("ButlerSleeperLiveWaiverComparisonBundleCli"));
        assertTrue(dispatch.contains("ButlerSleeperLiveWaiverGovernedExplanationLookupCli"));
        assertTrue(dispatch.contains("interactive Gradle task is not authorized for direct Java execution"));
        assertTrue(dispatch.contains("$repoRoot = [string]$env:BUTLER_APP_REPO_ROOT"));
        assertTrue(dispatch.contains("Push-Location $repoRoot"));
        assertTrue(dispatch.contains("& $java '-cp' $classPath $mainClass @mainArguments"));
        assertFalse(dispatch.contains("gradlew"));
        assertFalse(dispatch.contains("/refresh"));
        assertFalse(dispatch.contains("create_transaction"));
        assertFalse(dispatch.contains("submitTransaction"));
    }

    @Test
    void stagedRuntimeIsOwnedAndRemovedAfterWorkersStop() throws Exception {
        String core = source("scripts/butler-app-shell-core.ps1");

        int workerCleanup = core.indexOf("Stop-OwnedProcessTree -Process $backend.Process");
        int runtimeCleanup = core.indexOf("Remove-Item -LiteralPath $runtimeRoot -Recurse -Force", workerCleanup);
        assertTrue(workerCleanup >= 0 && runtimeCleanup > workerCleanup);
        assertTrue(core.contains("Restore-RuntimeLib"));
        assertTrue(core.contains("Restore-GradleOpts"));
    }

    @Test
    void bf704RuntimeFilesRemainAsciiOnly() throws Exception {
        for (String path : new String[]{
                "scripts/butler-app-shell-core.ps1",
                "scripts/butler-direct-java-dispatch.ps1",
                "scripts/butler-direct-java-gradle-proxy.cmd"
        }) {
            String script = source(path);
            byte[] encoded = script.getBytes(StandardCharsets.US_ASCII);
            assertEquals(script, new String(encoded, StandardCharsets.US_ASCII));
        }
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-704 test could not locate " + relativePath);
    }
}
