package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf845MatchupSingleFlightTest {

    @Test
    void passiveMatchupGetsItsOwnExistingExpensiveReadSingleFlightKey() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");

        int keyStart = worker.indexOf("function Get-ExpensiveReadSingleFlightKey");
        int keyEnd = worker.indexOf("function Invoke-ExpensiveReadSingleFlightGet", keyStart);
        assertTrue(keyStart >= 0 && keyEnd > keyStart);
        String keyBlock = worker.substring(keyStart, keyEnd);

        assertTrue(keyBlock.contains("'/matchup' { return 'MATCHUP' }"));
        assertFalse(keyBlock.contains("/matchup/autofill"));
        assertTrue(worker.contains("Butler.Expensive.SingleFlight.$PID.$League.$routeKey"));
        assertTrue(worker.contains("AddSeconds(5).Ticks"));
        assertTrue(worker.contains("if ([int]$proxied.StatusCode -eq 200)"));
    }

    @Test
    void onlyExactPassiveMatchupUsesTheSharedExpensiveReadHelper() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");

        assertTrue(worker.contains("$requestTarget -ceq '/league' -or $requestTarget -ceq '/matchup'"));
        assertTrue(worker.contains("Invoke-ExpensiveReadSingleFlightGet -Port $InnerPort -RequestTarget $requestTarget -League $LeagueId"));

        int dispatchStart = worker.indexOf("try {\n        $proxied = if ($requestTarget -ceq '/team')");
        assertTrue(dispatchStart >= 0);
        int dispatchEnd = worker.indexOf("        $body = $proxied.Body", dispatchStart);
        assertTrue(dispatchEnd > dispatchStart);
        String dispatch = worker.substring(dispatchStart, dispatchEnd);

        assertFalse(dispatch.contains("$requestTarget -ceq '/matchup/autofill'"));
        assertFalse(dispatch.contains("$path -eq '/matchup'"));
        assertTrue(dispatch.contains("else {\n            Invoke-AppCoreGet -Port $InnerPort -RequestTarget $requestTarget"));
    }

    @Test
    void matchupOptimizationDoesNotChangeWriteOrAutofillBoundaries() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");

        int keyStart = worker.indexOf("function Get-ExpensiveReadSingleFlightKey");
        int helperEnd = worker.indexOf("function Send-HttpResponse", keyStart);
        assertTrue(keyStart >= 0 && helperEnd > keyStart);
        String optimization = worker.substring(keyStart, helperEnd);

        assertFalse(optimization.contains("POST"));
        assertFalse(optimization.contains("submitTransaction"));
        assertFalse(optimization.contains("create_transaction"));
        assertFalse(optimization.contains("AutoFillLineupOptimizer"));
        assertFalse(optimization.contains("/matchup/autofill"));
    }

    @Test
    void bf845WorkerRemainsAsciiOnly() throws Exception {
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
        throw new IOException("BF-845 test could not locate " + relativePath);
    }
}
