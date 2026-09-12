package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf693OuterReadSingleFlightTest {

    @Test
    void exactRootWaiversAndLeagueReadsUseIndependentOuterSingleFlights() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");

        assertTrue(worker.contains("function Get-ExpensiveReadSingleFlightKey"));
        assertTrue(worker.contains("switch -CaseSensitive ($RequestTarget)"));
        assertTrue(worker.contains("'/' { return 'ROOT' }"));
        assertTrue(worker.contains("'/waivers' { return 'WAIVERS' }"));
        assertTrue(worker.contains("'/league' { return 'LEAGUE' }"));
        assertTrue(worker.contains("default { return $null }"));

        assertTrue(worker.contains("Local\\Butler.Expensive.Read.{0}.{1}"));
        assertTrue(worker.contains("$mutex.WaitOne(180000)"));
        assertTrue(worker.contains("Butler.Expensive.SingleFlight.$PID.$League.$routeKey"));
        assertTrue(worker.contains("[System.AppDomain]::CurrentDomain.GetData($cacheKey)"));
        assertTrue(worker.contains("[System.AppDomain]::CurrentDomain.SetData($cacheKey"));
        assertTrue(worker.contains("AddSeconds(5).Ticks"));
        assertTrue(worker.contains("if ([int]$proxied.StatusCode -eq 200)"));
        assertTrue(worker.contains("Invoke-ExpensiveReadSingleFlightGet -Port $InnerPort -RequestTarget $requestTarget -League $LeagueId"));
    }

    @Test
    void teamKeepsPredecessorSingleFlightAndIsNotMovedIntoBf693Helper() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");
        int bf693KeyStart = worker.indexOf("function Get-ExpensiveReadSingleFlightKey");
        int bf693InvokeStart = worker.indexOf("function Invoke-ExpensiveReadSingleFlightGet", bf693KeyStart);
        assertTrue(bf693KeyStart >= 0 && bf693InvokeStart > bf693KeyStart);
        String keyBlock = worker.substring(bf693KeyStart, bf693InvokeStart);

        assertFalse(keyBlock.contains("'/team'"));
        assertTrue(worker.contains("function Invoke-TeamSingleFlightGet"));
        assertTrue(worker.contains("if ($RequestTarget -cne '/team')"));
        assertTrue(worker.contains("Local\\Butler.Team.Read.{0}"));
        assertTrue(worker.contains("BF-691 BLOCKED: finite wait for the shared My Team read expired."));
        assertTrue(worker.contains("if ($requestTarget -ceq '/team')"));
        assertTrue(worker.contains("Invoke-TeamSingleFlightGet -Port $InnerPort -RequestTarget $requestTarget -League $LeagueId"));
    }

    @Test
    void queryDetailTradeHistoryHealthAndRefreshAreNotBf693Eligible() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");
        int keyStart = worker.indexOf("function Get-ExpensiveReadSingleFlightKey");
        int keyEnd = worker.indexOf("function Invoke-ExpensiveReadSingleFlightGet", keyStart);
        assertTrue(keyStart >= 0 && keyEnd > keyStart);
        String keyBlock = worker.substring(keyStart, keyEnd);

        assertFalse(keyBlock.contains("/waivers?"));
        assertFalse(keyBlock.contains("/league?"));
        assertFalse(keyBlock.contains("/trade"));
        assertFalse(keyBlock.contains("/history"));
        assertFalse(keyBlock.contains("/health"));
        assertFalse(keyBlock.contains("/refresh"));
        assertFalse(keyBlock.contains("Split('?')"));

        assertTrue(worker.contains("if ($path -eq '/health')"));
        assertTrue(worker.contains("if ($path -eq '/refresh')"));
        assertTrue(worker.contains("if ($requestTarget -cne '/refresh')"));
    }

    @Test
    void failuresDoNotCacheAndAlwaysReleaseBf693RouteMutex() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");
        int singleFlight = worker.indexOf("function Invoke-ExpensiveReadSingleFlightGet");
        int release = worker.indexOf("$mutex.ReleaseMutex()", singleFlight);
        int dispose = worker.indexOf("$mutex.Dispose()", singleFlight);
        assertTrue(singleFlight >= 0 && release > singleFlight && dispose > release);

        String block = worker.substring(singleFlight, dispose + "$mutex.Dispose()".length());
        assertTrue(block.contains("catch [System.Threading.AbandonedMutexException]"));
        assertTrue(block.contains("if ($lockTaken)"));
        assertTrue(block.contains("if ([int]$proxied.StatusCode -eq 200)"));
        assertFalse(block.contains("Start-Sleep"));
        assertFalse(block.contains("create_transaction"));
        assertFalse(block.contains("submitTransaction"));
        assertFalse(block.contains("waiver_budget"));
    }

    @Test
    void innerCoreWorkerRemainsRecoveredAndBf693OuterWorkerIsAsciiOnly() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");
        String inner = source("scripts/butler-app-core-pool-worker.ps1");

        assertFalse(inner.contains("Get-CoreReadSingleFlightKey"));
        assertFalse(inner.contains("Invoke-PreservedCoreSingleFlightGet"));
        assertTrue(inner.contains("$proxied = Invoke-PreservedCoreGet -RequestTarget $requestTarget"));
        assertTrue(worker.contains("Consume-RefreshToken -State $RefreshState -SubmittedToken $submittedToken"));
        assertFalse(worker.contains("create_transaction"));
        assertFalse(worker.contains("submitTransaction"));
        assertFalse(worker.contains("waiver_budget"));

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
        throw new IOException("BF-693 outer test could not locate " + relativePath);
    }
}
