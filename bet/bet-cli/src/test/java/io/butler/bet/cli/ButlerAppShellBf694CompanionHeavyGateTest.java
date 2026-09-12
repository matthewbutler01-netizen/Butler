package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf694CompanionHeavyGateTest {

    @Test
    void companionOwnersShareOneFiniteGateAfterRouteCacheMiss() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");
        int functionStart = worker.indexOf("function Invoke-ExpensiveReadSingleFlightGet");
        int functionEnd = worker.indexOf("function Send-HttpResponse", functionStart);
        assertTrue(functionStart >= 0 && functionEnd > functionStart);
        String block = worker.substring(functionStart, functionEnd);

        int cacheCheck = block.indexOf("if ($null -ne $cached -and [long]$cached.ExpiresUtcTicks -gt $nowTicks)");
        int companionCreate = block.indexOf("Local\\Butler.Companion.Heavy.{0}");
        int coreRead = block.indexOf("$proxied = Invoke-AppCoreGet -Port $Port -RequestTarget $RequestTarget");
        assertTrue(cacheCheck >= 0 && companionCreate > cacheCheck && coreRead > companionCreate);
        assertTrue(block.contains("$companionMutex.WaitOne(180000)"));
        assertTrue(block.contains("BF-694 BLOCKED: finite wait for companion heavy read capacity expired."));
        assertTrue(block.contains("catch [System.Threading.AbandonedMutexException]"));
        assertTrue(block.contains("$companionMutex.ReleaseMutex()"));
        assertTrue(block.contains("$companionMutex.Dispose()"));
    }

    @Test
    void teamAndNonHeavyRoutesStayOutsideCompanionGate() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");
        int teamStart = worker.indexOf("function Invoke-TeamSingleFlightGet");
        int teamEnd = worker.indexOf("function Get-ExpensiveReadSingleFlightKey", teamStart);
        String teamBlock = worker.substring(teamStart, teamEnd);
        assertFalse(teamBlock.contains("Butler.Companion.Heavy"));

        int keyStart = worker.indexOf("function Get-ExpensiveReadSingleFlightKey");
        int keyEnd = worker.indexOf("function Invoke-ExpensiveReadSingleFlightGet", keyStart);
        String keyBlock = worker.substring(keyStart, keyEnd);
        assertTrue(keyBlock.contains("'/' { return 'ROOT' }"));
        assertTrue(keyBlock.contains("'/waivers' { return 'WAIVERS' }"));
        assertTrue(keyBlock.contains("'/league' { return 'LEAGUE' }"));
        assertFalse(keyBlock.contains("'/team'"));
        assertFalse(keyBlock.contains("/health"));
        assertFalse(keyBlock.contains("/trade"));
        assertFalse(keyBlock.contains("/history"));
        assertFalse(keyBlock.contains("/refresh"));
    }

    @Test
    void predecessorSingleFlightsRefreshBoundaryAndRecoveredInnerWorkerRemain() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");
        String inner = source("scripts/butler-app-core-pool-worker.ps1");

        assertTrue(worker.contains("Local\\Butler.Team.Read.{0}"));
        assertTrue(worker.contains("Local\\Butler.Expensive.Read.{0}.{1}"));
        assertTrue(worker.contains("Butler.Expensive.SingleFlight.$PID.$League.$routeKey"));
        assertTrue(worker.contains("AddSeconds(5).Ticks"));
        assertTrue(worker.contains("Consume-RefreshToken -State $RefreshState -SubmittedToken $submittedToken"));
        assertTrue(worker.contains("if ($requestTarget -cne '/refresh')"));
        assertFalse(inner.contains("Butler.Companion.Heavy"));
        assertTrue(inner.contains("$proxied = Invoke-PreservedCoreGet -RequestTarget $requestTarget"));
        assertFalse(worker.contains("create_transaction"));
        assertFalse(worker.contains("submitTransaction"));
        assertFalse(worker.contains("waiver_budget"));
    }

    @Test
    void bf694WorkerRemainsAsciiOnly() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");
        byte[] encoded = worker.getBytes(StandardCharsets.US_ASCII);
        assertEquals(worker, new String(encoded, StandardCharsets.US_ASCII));
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
        throw new IOException("BF-694 test could not locate " + relativePath);
    }
}
