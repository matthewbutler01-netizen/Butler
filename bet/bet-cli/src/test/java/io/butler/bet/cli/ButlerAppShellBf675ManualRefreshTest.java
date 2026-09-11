package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf675ManualRefreshTest {

    private static final List<String> ORDERED_TASKS = List.of(
        ":bet:bet-cli:sleeperLiveWaiverSnapshotSync",
        ":bet:bet-cli:sleeperLiveWaiverMarketAttentionSync",
        ":bet:bet-cli:sleeperLiveWaiverProductionHydration",
        ":bet:bet-cli:sleeperLiveWaiverAvailabilitySync",
        ":bet:bet-cli:sleeperLiveWaiverCurrentWeekStatSync",
        ":bet:bet-cli:sleeperLiveWaiverTargetRosterProductionHydration",
        ":bet:bet-cli:sleeperLiveWaiverFinalRecommendationBundle",
        ":bet:bet-cli:sleeperLiveWaiverRecommendationAuditCapture",
        ":bet:bet-cli:sleeperLiveWaiverLatestGovernedDecisionSummary");

    @Test
    void dashboardLinksToGetConfirmationAndOnlyExactPostRefreshCanWrite() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");
        String worker = script("scripts/butler-app-request-worker.ps1");
        String refresh = script("scripts/butler-decision-refresh.ps1");

        assertTrue(shell.contains("butler-decision-refresh.ps1"));
        assertTrue(shell.contains("sleeper-live-waiver-no-transaction-refresh.ps1"));
        assertTrue(shell.contains(". $decisionRefresh"));
        assertTrue(worker.contains("$path -eq '/refresh'"));
        assertTrue(refresh.contains("href=\"/refresh\">Check for a new decision"));
        assertTrue(refresh.contains("method=\"post\" action=\"/refresh\""));
        assertTrue(refresh.contains("type=\"hidden\" name=\"token\""));
        assertTrue(worker.contains("if ($parts[0] -eq 'POST')"));
        assertTrue(worker.contains("if ($requestTarget -cne '/refresh')"));
        assertTrue(worker.contains("if ($parts[0] -ne 'GET')"));
        assertTrue(worker.contains("-Body 'GET only'"));

        int postGate = worker.indexOf("if ($parts[0] -eq 'POST')");
        int genericNonGet = worker.indexOf("if ($parts[0] -ne 'GET')");
        assertTrue(postGate >= 0 && genericNonGet > postGate,
            "BF-675 must special-case only exact POST /refresh before preserving the GET-only fallback");
    }

    @Test
    void postUsesCryptographicOneUseTokenAndInvalidatesBeforeRunner() throws Exception {
        String worker = script("scripts/butler-app-request-worker.ps1");
        String refresh = script("scripts/butler-decision-refresh.ps1");

        assertTrue(refresh.contains("RandomNumberGenerator]::Create()"));
        assertTrue(refresh.contains("refresh POST must contain only the one-use token"));
        assertTrue(refresh.contains("Content-Length"));
        assertTrue(refresh.contains("$contentLength -gt 4096"));
        assertTrue(refresh.contains("application/x-www-form-urlencoded"));
        assertTrue(worker.contains("$SubmittedToken -cne [string]$State.Token"));
        assertTrue(worker.contains("missing, expired, replayed, or invalid"));

        int validate = worker.indexOf("$SubmittedToken -cne [string]$State.Token");
        int rotate = worker.indexOf("$State.Token = New-DecisionRefreshToken", validate);
        int consume = worker.indexOf("Consume-RefreshToken -State $RefreshState -SubmittedToken $submittedToken");
        int run = worker.indexOf("Invoke-DecisionRefreshRunner", consume);
        assertTrue(validate >= 0 && rotate > validate && consume > rotate && run > consume,
            "BF-675 must atomically consume/rotate the token before any refresh runner invocation");
    }

    @Test
    void runnerRequiresExactGovernedNoTransactionStateBeforeBf602() throws Exception {
        String runner = script("scripts/sleeper-live-waiver-no-transaction-refresh.ps1");

        assertTrue(runner.contains("$decisionState -ceq 'NO_TRANSACTION_TO_ACT_ON'"));
        assertTrue(runner.contains("$bf629State -cne 'NO_TRANSACTION_TO_REVALIDATE'"));
        assertTrue(runner.contains("$bf631State -cne 'LATEST_EVIDENCE_LINEAGE_VERIFIED'"));
        assertTrue(runner.contains("No BF-602/BF-603/etc. write stage was executed"));

        int preflight = runner.indexOf("$decisionState = Get-Bf676SingleField");
        int noTransactionGate = runner.indexOf("$decisionState -ceq 'NO_TRANSACTION_TO_ACT_ON'", preflight);
        int firstWrite = runner.indexOf("Task = ':bet:bet-cli:sleeperLiveWaiverSnapshotSync'", noTransactionGate);
        assertTrue(preflight >= 0 && noTransactionGate > preflight && firstWrite > noTransactionGate,
            "BF-675 no-transaction authorization must still be checked before BF-602");
    }

    @Test
    void runnerPinsEstablishedNineStageOrderAndStopsOnNativeFailure() throws Exception {
        String runner = script("scripts/sleeper-live-waiver-no-transaction-refresh.ps1");
        int stageBlock = runner.indexOf("$steps = @(");
        assertTrue(stageBlock >= 0);
        String stages = runner.substring(stageBlock);

        int previous = -1;
        for (String task : ORDERED_TASKS) {
            int current = stages.indexOf(task);
            assertTrue(current > previous, "BF-675 stage order drifted at " + task);
            previous = current;
        }

        assertTrue(runner.contains("$previousErrorActionPreference = $ErrorActionPreference"));
        assertTrue(runner.contains("$ErrorActionPreference = 'Continue'"));
        assertTrue(runner.contains("$exitCode = $LASTEXITCODE"));
        assertTrue(runner.contains("$ErrorActionPreference = $previousErrorActionPreference"));
        assertTrue(runner.contains("if ($exitCode -ne 0)"));
        assertTrue(runner.contains("No later stage was executed."));
    }

    @Test
    void browserBoundaryHasNoArbitraryCommandOrSleeperTransactionExecution() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");
        String worker = script("scripts/butler-app-request-worker.ps1");
        String refresh = script("scripts/butler-decision-refresh.ps1");
        String runner = script("scripts/sleeper-live-waiver-no-transaction-refresh.ps1");

        assertTrue(shell.contains("[System.Net.IPAddress]::Parse('127.0.0.1')"));
        assertTrue(worker.contains("form-action 'self'"));
        assertFalse(refresh.contains("<script"));
        assertFalse(refresh.contains("javascript:"));
        assertFalse(refresh.contains("Invoke-Expression"));
        assertFalse(shell.contains("Invoke-Expression"));
        assertFalse(worker.contains("Invoke-Expression"));
        assertFalse(runner.contains("Invoke-Expression"));
        assertFalse(runner.contains("create_transaction"));
        assertFalse(runner.contains("submitTransaction"));
        assertFalse(refresh.contains("create_transaction"));
        assertFalse(shell.contains("create_transaction"));
        assertFalse(worker.contains("create_transaction"));
        assertTrue(runner.contains("This runner never submits, cancels, or replaces a Sleeper transaction and never sets FAAB."));
        assertTrue(refresh.contains("This does not submit a waiver move to Sleeper."));
    }

    @Test
    void bf675FilesRemainAsciiOnly() throws Exception {
        assertAscii(script("scripts/butler-app-shell.ps1"));
        assertAscii(script("scripts/butler-app-request-worker.ps1"));
        assertAscii(script("scripts/butler-decision-refresh.ps1"));
        assertAscii(script("scripts/sleeper-live-waiver-no-transaction-refresh.ps1"));
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
        throw new IOException("BF-675 test could not locate " + relativePath);
    }
}
