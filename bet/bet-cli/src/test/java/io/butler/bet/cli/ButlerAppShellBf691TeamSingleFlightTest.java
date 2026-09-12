package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf691TeamSingleFlightTest {

    @Test
    void exactTeamReadsUseOneFiniteProcessLocalSingleFlight() throws Exception {
        String worker = script("scripts/butler-app-request-worker.ps1");

        assertTrue(worker.contains("function Invoke-TeamSingleFlightGet"));
        assertTrue(worker.contains("if ($RequestTarget -cne '/team')"));
        assertTrue(worker.contains("Local\\Butler.Team.Read.{0}"));
        assertTrue(worker.contains("$mutex.WaitOne(180000)"));
        assertTrue(worker.contains("BF-691 BLOCKED: finite wait for the shared My Team read expired."));
        assertTrue(worker.contains("[System.AppDomain]::CurrentDomain.GetData($cacheKey)"));
        assertTrue(worker.contains("[System.AppDomain]::CurrentDomain.SetData($cacheKey"));
        assertTrue(worker.contains("AddSeconds(5).Ticks"));
        assertTrue(worker.contains("if ([int]$proxied.StatusCode -eq 200)"));
        assertTrue(worker.contains("Invoke-TeamSingleFlightGet -Port $InnerPort -RequestTarget $requestTarget -League $LeagueId"));
        assertTrue(worker.contains("if ($requestTarget -ceq '/team')"));
    }

    @Test
    void failureAndShutdownPathsAlwaysReleaseTheTeamMutex() throws Exception {
        String worker = script("scripts/butler-app-request-worker.ps1");

        int singleFlight = worker.indexOf("function Invoke-TeamSingleFlightGet");
        int release = worker.indexOf("$mutex.ReleaseMutex()", singleFlight);
        int dispose = worker.indexOf("$mutex.Dispose()", singleFlight);
        assertTrue(singleFlight >= 0 && release > singleFlight && dispose > release);
        assertTrue(worker.contains("catch [System.Threading.AbandonedMutexException]"));
        assertTrue(worker.contains("if ($lockTaken)"));
        assertFalse(worker.contains("Start-Sleep", singleFlight));
    }

    @Test
    void teamSingleFlightDoesNotChangeTeamEvidenceOrRefreshGovernance() throws Exception {
        String worker = script("scripts/butler-app-request-worker.ps1");
        String preserved = script("scripts/butler-app-shell-core-single.ps1");

        assertTrue(preserved.contains(":bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit"));
        assertTrue(preserved.contains("league team-context $LeagueId"));
        assertTrue(preserved.contains("league roster-strength $LeagueId"));
        assertTrue(preserved.contains("league positional-pressure $LeagueId"));
        assertTrue(preserved.contains("league team-posture $LeagueId $($rosterView.Season)"));
        assertTrue(preserved.contains("league future-capital $LeagueId"));
        assertTrue(preserved.contains("No new team score or strategy model is created here."));
        assertTrue(preserved.contains("READ ONLY."));

        assertTrue(worker.contains("Consume-RefreshToken -State $RefreshState -SubmittedToken $submittedToken"));
        assertTrue(worker.contains("$State.Token = New-DecisionRefreshToken"));
        assertTrue(worker.contains("if ($requestTarget -cne '/refresh')"));
        assertFalse(worker.contains("create_transaction"));
        assertFalse(worker.contains("submitTransaction"));
        assertFalse(worker.contains("waiver_budget"));
    }

    @Test
    void bf691WorkerRemainsAsciiOnly() throws Exception {
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
        throw new IOException("BF-691 test could not locate " + relativePath);
    }
}
