package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf689BoundedConcurrencyTest {

    @Test
    void shellDispatchesAcceptedClientsThroughFiniteRunspacePool() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");

        assertTrue(shell.contains("$maxRequestWorkers = 8"));
        assertTrue(shell.contains("CreateRunspacePool(1, $maxRequestWorkers)"));
        assertTrue(shell.contains("while ($activeRequests.Count -ge $maxRequestWorkers)"));
        assertTrue(shell.contains("$client = $listener.AcceptTcpClient()"));
        assertTrue(shell.contains("$powerShell.RunspacePool = $requestPool"));
        assertTrue(shell.contains("$powerShell.AddCommand($requestWorker)"));
        assertTrue(shell.contains("$handle = $powerShell.BeginInvoke()"));
        assertTrue(shell.contains("Remove-CompletedRequestJobs -WaitForOne"));
    }

    @Test
    void workerOwnsRequestLifecycleInsteadOfBlockingListenerLoop() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");
        String worker = script("scripts/butler-app-request-worker.ps1");

        assertTrue(worker.contains("$stream = $Client.GetStream()"));
        assertTrue(worker.contains("$requestLine = $reader.ReadLine()"));
        assertTrue(worker.contains("if ($path -eq '/health')"));
        assertTrue(worker.contains("if ($path -eq '/history')"));
        assertTrue(worker.contains("if ($path -eq '/trade')"));
        assertTrue(worker.contains("Invoke-AppCoreGet -Port $InnerPort -RequestTarget $requestTarget"));

        assertFalse(shell.contains("$stream = $client.GetStream()"));
        assertFalse(shell.contains("$requestLine = $reader.ReadLine()"));
    }

    @Test
    void refreshTokenIsSharedLockedAndConsumedBeforeRefreshExecution() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");
        String worker = script("scripts/butler-app-request-worker.ps1");

        assertTrue(shell.contains("[hashtable]::Synchronized(@{ Token = $decisionRefreshToken })"));
        assertTrue(worker.contains("[System.Threading.Monitor]::Enter($State.SyncRoot)"));
        assertTrue(worker.contains("[System.Threading.Monitor]::Exit($State.SyncRoot)"));
        assertTrue(worker.contains("$SubmittedToken -cne [string]$State.Token"));
        assertTrue(worker.contains("$State.Token = New-DecisionRefreshToken"));
        assertTrue(worker.contains("Consume-RefreshToken -State $RefreshState -SubmittedToken $submittedToken"));

        int consume = worker.indexOf("Consume-RefreshToken -State $RefreshState -SubmittedToken $submittedToken");
        int execute = worker.indexOf("Invoke-DecisionRefreshRunner -LeagueId $LeagueId -RunnerPath $DecisionRefreshRunner");
        assertTrue(consume >= 0 && execute > consume, "one-use token must be consumed before governed refresh execution");
    }

    @Test
    void workerPreservesReadOnlyAndLoopbackBoundaries() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");
        String worker = script("scripts/butler-app-request-worker.ps1");

        assertTrue(shell.contains("[System.Net.IPAddress]::Parse('127.0.0.1')"));
        assertTrue(shell.contains("[System.Net.Sockets.TcpListener]::new($loopback, $Port)"));
        assertTrue(worker.contains("if ($parts[0] -ne 'GET')"));
        assertTrue(worker.contains("if ($parts[0] -eq 'POST')"));
        assertTrue(worker.contains("if ($requestTarget -cne '/refresh')"));
        assertTrue(worker.contains("No Butler or Sleeper write was executed."));
        assertFalse(worker.contains("transactions/"));
        assertFalse(worker.contains("waiver_budget"));
    }

    @Test
    void workerPoolAndScriptsRemainAsciiOnly() throws Exception {
        assertAscii(script("scripts/butler-app-shell.ps1"));
        assertAscii(script("scripts/butler-app-request-worker.ps1"));
    }

    private static void assertAscii(String text) {
        byte[] encoded = text.getBytes(StandardCharsets.US_ASCII);
        assertEquals(text, new String(encoded, StandardCharsets.US_ASCII));
    }

    private static String script(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-689 test could not locate " + relativePath);
    }
}
