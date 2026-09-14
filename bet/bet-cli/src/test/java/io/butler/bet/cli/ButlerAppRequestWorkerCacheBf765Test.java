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
        String wrapper = source("scripts/butler-app-request-worker.ps1");

        assertTrue(wrapper.contains("ButlerBf765PublicRequestWorkerScriptBlock"));
        assertTrue(wrapper.contains("butler-app-request-worker-impl.ps1"));
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
    void canonicalImplementationRetainsPublicRoutingAndSafetyContracts() throws Exception {
        String worker = source("scripts/butler-app-request-worker-impl.ps1");

        assertTrue(worker.contains("Local\\Butler.Team.Read.{0}"));
        assertTrue(worker.contains("Local\\Butler.Expensive.Read.{0}.{1}"));
        assertTrue(worker.contains("Local\\Butler.Companion.Heavy.{0}"));
        assertTrue(worker.contains("Consume-RefreshToken -State $RefreshState -SubmittedToken $submittedToken"));
        assertTrue(worker.contains("BUTLER_APP_SHELL_VERIFIED"));
        assertTrue(worker.contains("if ($path -eq '/refresh')"));
        assertTrue(worker.contains("if ($requestTarget -cne '/refresh')"));
        assertTrue(worker.contains("Invoke-AppCoreGet -Port $InnerPort -RequestTarget $requestTarget"));
        assertFalse(worker.contains("create_transaction"));
        assertFalse(worker.contains("submitTransaction"));
        assertFalse(worker.contains("waiver_budget"));
    }

    @Test
    void publicShellKeepsExistingEightRunspaceSchedulerAndWrapperEntrypoint() throws Exception {
        String shell = source("scripts/butler-app-shell.ps1");

        assertTrue(shell.contains("$requestWorker = Join-Path $scriptDir 'butler-app-request-worker.ps1'"));
        assertTrue(shell.contains("$maxRequestWorkers = 8"));
        assertTrue(shell.contains("CreateRunspacePool(1, $maxRequestWorkers)"));
        assertTrue(shell.contains("$powerShell.AddCommand($requestWorker)"));
        assertTrue(shell.contains("$handle = $powerShell.BeginInvoke()"));
        assertFalse(shell.contains("butler-app-request-worker-impl.ps1"));
    }

    @Test
    void bf765WindowsSourcesRemainAsciiOnly() throws Exception {
        assertAscii(source("scripts/butler-app-request-worker.ps1"));
        assertAscii(source("scripts/butler-app-request-worker-impl.ps1"));
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