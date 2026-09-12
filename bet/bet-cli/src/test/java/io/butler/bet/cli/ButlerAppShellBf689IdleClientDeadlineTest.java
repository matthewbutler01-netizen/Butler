package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf689IdleClientDeadlineTest {

    @Test
    void acceptedLoopbackClientsReceiveFiniteReadDeadlineBeforeWorkerDispatch() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");

        int accept = shell.indexOf("$client = $listener.AcceptTcpClient()");
        int receiveTimeout = shell.indexOf("$client.ReceiveTimeout = 3000", accept);
        int sendTimeout = shell.indexOf("$client.SendTimeout = 10000", receiveTimeout);
        int dispatch = shell.indexOf("$handle = $powerShell.BeginInvoke()", sendTimeout);

        assertTrue(accept >= 0);
        assertTrue(receiveTimeout > accept, "idle/partial clients must get a finite receive deadline immediately after accept");
        assertTrue(sendTimeout > receiveTimeout, "response writes must also remain bounded");
        assertTrue(dispatch > sendTimeout, "socket deadlines must be configured before the request enters the worker pool");
    }

    @Test
    void idleClientCorrectionPreservesFinitePoolAndRefreshSerialization() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");
        String worker = script("scripts/butler-app-request-worker.ps1");

        assertTrue(shell.contains("$maxRequestWorkers = 8"));
        assertTrue(shell.contains("while ($activeRequests.Count -ge $maxRequestWorkers)"));
        assertTrue(worker.contains("[System.Threading.Monitor]::Enter($State.SyncRoot)"));
        assertTrue(worker.contains("$State.Token = New-DecisionRefreshToken"));
        assertTrue(worker.contains("Consume-RefreshToken -State $RefreshState -SubmittedToken $submittedToken"));
    }

    @Test
    void correctedShellRemainsAsciiOnly() throws Exception {
        assertAscii(script("scripts/butler-app-shell.ps1"));
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
        throw new IOException("BF-689 idle-client deadline test could not locate " + relativePath);
    }
}
