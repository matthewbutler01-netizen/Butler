package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf693CoreReadSingleFlightTest {

    @Test
    void exactExpensiveCoreReadsUseIndependentFiniteSingleFlights() throws Exception {
        String worker = source("scripts/butler-app-core-pool-worker.ps1");

        assertTrue(worker.contains("function Get-CoreReadSingleFlightKey"));
        assertTrue(worker.contains("switch -CaseSensitive ($RequestTarget)"));
        assertTrue(worker.contains("'/' { return 'ROOT' }"));
        assertTrue(worker.contains("'/team' { return 'TEAM' }"));
        assertTrue(worker.contains("'/waivers' { return 'WAIVERS' }"));
        assertTrue(worker.contains("'/league' { return 'LEAGUE' }"));
        assertTrue(worker.contains("default { return $null }"));

        assertTrue(worker.contains("Local\\Butler.Core.Read.{0}.{1}"));
        assertTrue(worker.contains("$PID, $routeKey"));
        assertTrue(worker.contains("$mutex.WaitOne(180000)"));
        assertTrue(worker.contains("Butler.Core.SingleFlight.$PID.$routeKey"));
        assertTrue(worker.contains("[System.AppDomain]::CurrentDomain.GetData($cacheKey)"));
        assertTrue(worker.contains("[System.AppDomain]::CurrentDomain.SetData($cacheKey"));
        assertTrue(worker.contains("AddSeconds(5).Ticks"));
        assertTrue(worker.contains("if ([int]$proxied.StatusCode -eq 200)"));
        assertTrue(worker.contains("Invoke-PreservedCoreSingleFlightGet -RequestTarget $requestTarget"));
    }

    @Test
    void queryDetailRefreshAndOtherRoutesAreNotEligibleForSharedCoreReuse() throws Exception {
        String worker = source("scripts/butler-app-core-pool-worker.ps1");
        int keyStart = worker.indexOf("function Get-CoreReadSingleFlightKey");
        int keyEnd = worker.indexOf("function Invoke-PreservedCoreSingleFlightGet", keyStart);
        assertTrue(keyStart >= 0 && keyEnd > keyStart);
        String keyBlock = worker.substring(keyStart, keyEnd);

        assertFalse(keyBlock.contains("/waivers/candidate"));
        assertFalse(keyBlock.contains("/trade"));
        assertFalse(keyBlock.contains("/history"));
        assertFalse(keyBlock.contains("/refresh"));
        assertFalse(keyBlock.contains("Split('?')"));

        assertTrue(worker.contains("if ($path -eq '/refresh')"));
        assertTrue(worker.indexOf("if ($path -eq '/refresh')") < worker.indexOf("Invoke-PreservedCoreSingleFlightGet -RequestTarget $requestTarget"));
    }

    @Test
    void failuresDoNotCacheAndAlwaysReleaseRouteLock() throws Exception {
        String worker = source("scripts/butler-app-core-pool-worker.ps1");
        int singleFlight = worker.indexOf("function Invoke-PreservedCoreSingleFlightGet");
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
    void predecessorTeamSingleFlightAndRefreshGovernanceRemainPresent() throws Exception {
        String outer = source("scripts/butler-app-request-worker.ps1");
        String core = source("scripts/butler-app-shell-core.ps1");

        assertTrue(outer.contains("function Invoke-TeamSingleFlightGet"));
        assertTrue(outer.contains("if ($requestTarget -ceq '/team')"));
        assertTrue(outer.contains("$mutex.WaitOne(180000)"));
        assertTrue(outer.contains("AddSeconds(5).Ticks"));
        assertTrue(outer.contains("Consume-RefreshToken -State $RefreshState -SubmittedToken $submittedToken"));
        assertTrue(outer.contains("if ($requestTarget -cne '/refresh')"));
        assertTrue(core.contains("$maxCoreWorkers = 6"));
    }

    @Test
    void bf693WorkerRemainsAsciiOnly() throws Exception {
        String worker = source("scripts/butler-app-core-pool-worker.ps1");
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
        throw new IOException("BF-693 test could not locate " + relativePath);
    }
}
