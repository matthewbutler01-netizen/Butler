package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppRequestWorkerCacheBf765Test {

    @Test
    void wrapperCachesOneParsedImplementationPerPersistentRunspace() throws Exception {
        String wrapper = source("scripts/butler-app-request-worker-cache.ps1");

        assertTrue(wrapper.contains("ButlerBf765PublicRequestWorkerScriptBlock"));
        assertTrue(wrapper.contains("butler-app-request-worker.ps1"));
        assertTrue(wrapper.contains("[scriptblock]::Create($implementation)"));
        assertTrue(wrapper.contains("Set-Variable -Name $cacheName -Scope Global -Value $worker"));
        assertTrue(wrapper.contains("$worker -isnot [scriptblock]"));
        assertTrue(wrapper.contains("& $worker"));

        assertTrue(wrapper.contains("-Client $Client"));
        assertTrue(wrapper.contains("-LeagueId $LeagueId"));
        assertTrue(wrapper.contains("-InnerPort $InnerPort"));
        assertTrue(wrapper.contains("-TradeHost $TradeHost"));
        assertTrue(wrapper.contains("-TradeLab $TradeLab"));
        assertTrue(wrapper.contains("-History $History"));
        assertTrue(wrapper.contains("-Detail $Detail"));
        assertTrue(wrapper.contains("-DecisionRefresh $DecisionRefresh"));
        assertTrue(wrapper.contains("-DecisionRefreshRunner $DecisionRefreshRunner"));
        assertTrue(wrapper.contains("-RepoRoot $RepoRoot"));
        assertTrue(wrapper.contains("-RefreshState $RefreshState"));

        assertFalse(wrapper.contains("Local\\Butler.Team.Read"));
        assertFalse(wrapper.contains("Local\\Butler.Expensive.Read"));
        assertFalse(wrapper.contains("Butler.Companion.Heavy"));
        assertFalse(wrapper.contains("Consume-RefreshToken"));
        assertFalse(wrapper.contains("function Invoke-AppCoreGet"));
        assertFalse(wrapper.contains("create_transaction"));
        assertFalse(wrapper.contains("submitTransaction"));
        assertFalse(wrapper.contains("waiver_budget"));
    }

    @Test
    void cacheInitializationErrorsProduceSafeLocal503InsteadOfDroppedConnection() throws Exception {
        // BF-1124: missing/stale parsed worker and module staging failures
        // must be observable by the local read-only readiness checker.
        String wrapper = source("scripts/butler-app-request-worker-cache.ps1");
        int helper = wrapper.indexOf("function Send-ButlerWorkerInitializationFailure");
        int init = wrapper.indexOf("try {\n$cacheName = 'ButlerBf765PublicRequestWorkerScriptBlock'");
        int fallback = wrapper.indexOf("catch {\n    Send-ButlerWorkerInitializationFailure -Client $Client");
        int worker = wrapper.indexOf("& $worker \`");

        assertTrue(helper >= 0 && init > helper && fallback > init && worker > fallback);
        String initialization = wrapper.substring(init, fallback);
        assertFalse(initialization.contains("$Client.Close()"),
            "the initialization error handler must respond before closing the client");

        String fallbackFunction = wrapper.substring(helper, init);
        assertTrue(fallbackFunction.contains("HTTP/1.1 503 Service Unavailable"));
        assertTrue(fallbackFunction.contains("X-Butler-Failure-Layer: PUBLIC_WORKER"));
        assertTrue(fallbackFunction.contains("Cache-Control: no-store"));
        assertTrue(fallbackFunction.contains("Content-Security-Policy: default-src 'none'"));
        assertTrue(fallbackFunction.contains("Content-Length: $($bodyBytes.Length)"));
        assertTrue(fallbackFunction.contains("$stream.Write($headerBytes"));
        assertTrue(fallbackFunction.contains("$stream.Write($bodyBytes"));
        assertTrue(fallbackFunction.contains("No Sleeper transaction was submitted by this page."));
        assertFalse(fallbackFunction.contains("$_.Exception.Message"));
        assertFalse(fallbackFunction.contains("$LeagueId"));
        assertFalse(fallbackFunction.contains("$RepoRoot"));
        assertFalse(fallbackFunction.contains("submitTransaction"));
    }

    @Test
    void canonicalImplementationRetainsPublicRoutingAndSafetyContractsAtHistoricalPath() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");

        assertTrue(worker.contains("Local\\Butler.Team.Read.{0}"));
        assertTrue(worker.contains("Local\\Butler.Expensive.Read.{0}.{1}"));
        assertTrue(worker.contains("Local\\Butler.Companion.Heavy.{0}"));
        assertTrue(worker.contains("Consume-RefreshToken -State $RefreshState -SubmittedToken $submittedToken"));
        assertTrue(worker.contains("\"service\":\"butler-app-shell\""));
        assertTrue(worker.contains("if ($path -eq '/refresh')"));
        assertTrue(worker.contains("if ($requestTarget -cne '/refresh')"));
        assertTrue(worker.contains("Invoke-AppCoreGet -Port $InnerPort -RequestTarget $requestTarget"));
        assertFalse(worker.contains("create_transaction"));
        assertFalse(worker.contains("submitTransaction"));
        assertFalse(worker.contains("waiver_budget"));
    }

    @Test
    void publicShellKeepsExistingEightRunspaceSchedulerAndUsesCachedEntrypoint() throws Exception {
        String shell = source("scripts/butler-app-shell.ps1");

        String historicalPath = "$requestWorker = Join-Path $scriptDir 'butler-app-request-worker.ps1'";
        String cachedPath = "$requestWorker = Join-Path $scriptDir 'butler-app-request-worker-cache.ps1'";
        assertTrue(shell.contains(historicalPath));
        assertTrue(shell.contains("$requestWorkerImplementation = $requestWorker"));
        assertTrue(shell.contains(cachedPath));
        assertTrue(shell.indexOf(cachedPath) > shell.indexOf(historicalPath));
        assertTrue(shell.contains("$maxRequestWorkers = 8"));
        assertTrue(shell.contains("CreateRunspacePool(1, $maxRequestWorkers)"));
        assertTrue(shell.contains("$powerShell.AddCommand($requestWorker)"));
        assertTrue(shell.contains("$handle = $powerShell.BeginInvoke()"));
        assertFalse(shell.contains("butler-app-request-worker-impl.ps1"));
    }

    @Test
    void bf765WindowsSourcesRemainAsciiOnly() throws Exception {
        assertAscii(source("scripts/butler-app-request-worker.ps1"));
        assertAscii(source("scripts/butler-app-request-worker-cache.ps1"));
        assertAscii(source("scripts/butler-app-shell.ps1"));
    }

    private static void assertAscii(String text) {
        byte[] encoded = text.getBytes(StandardCharsets.US_ASCII);
        assertEquals(text, new String(encoded, StandardCharsets.US_ASCII));
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
        throw new IOException("BF-765 test could not locate " + relativePath);
    }
}