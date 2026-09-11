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

class ButlerAppShellBf676WarningRefreshTest {

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
    void warningStateRequiresBf629Bf631Bf635AndExactBf636Plan() throws Exception {
        String runner = script("scripts/sleeper-live-waiver-no-transaction-refresh.ps1");

        assertTrue(runner.contains("$decisionState -ceq 'CURRENT_REFRESH_RECOMMENDED'"));
        assertTrue(runner.contains("$bf629State -cne 'LIVE_ACTIONABLE_VERIFIED'"));
        assertTrue(runner.contains("$bf631State -cne 'LATEST_EVIDENCE_LINEAGE_VERIFIED'"));
        assertTrue(runner.contains("Refresh trigger:"));
        assertTrue(runner.contains("BF-603 market and BF-602 waiver evidence exceed 21600 seconds"));
        assertTrue(runner.contains("BF-603 market evidence exceeds 21600 seconds"));
        assertTrue(runner.contains("BF-602 waiver evidence exceeds 21600 seconds"));
        assertTrue(runner.contains("sleeper-live-waiver-manual-refresh-plan-v1-bf635-explicit-operator-only-no-execution"));
        assertTrue(runner.contains("MANUAL_REFRESH_PLAN_READY"));
        assertTrue(runner.contains("Assert-Bf676WarningRefreshPlan -Text $preflight"));

        int stateGate = runner.indexOf("$decisionState -ceq 'CURRENT_REFRESH_RECOMMENDED'");
        int planGate = runner.indexOf("Assert-Bf676WarningRefreshPlan -Text $preflight", stateGate);
        int firstWrite = runner.indexOf("Task = ':bet:bet-cli:sleeperLiveWaiverSnapshotSync'", planGate);
        assertTrue(stateGate >= 0 && planGate > stateGate && firstWrite > planGate,
            "BF-676 must validate governed warning state and BF-636 plan before BF-602");
    }

    @Test
    void warningPlanReconcilesExactNineGovernedStepsAndLeagueBoundCommands() throws Exception {
        String runner = script("scripts/sleeper-live-waiver-no-transaction-refresh.ps1");

        assertTrue(runner.contains("$planStepCount -ne 9"));
        assertTrue(runner.contains("[regex]::Matches($Text, [regex]::Escape($line)).Count -ne 1"));
        assertTrue(runner.contains(".\\gradlew.bat :bet:bet-cli:$($step.Task) --args=`\"$LeagueId`\""));
        assertTrue(runner.contains("$Text.IndexOf($line, $searchFrom, [System.StringComparison]::Ordinal)"));
        assertTrue(runner.contains("$Text.IndexOf($command, $lineIndex, [System.StringComparison]::Ordinal)"));

        String[] exactSteps = {
            "Order = 1; Bf = 'BF-602'; Mode = 'BUTLER_WRITE'; Task = 'sleeperLiveWaiverSnapshotSync'",
            "Order = 2; Bf = 'BF-603'; Mode = 'BUTLER_WRITE'; Task = 'sleeperLiveWaiverMarketAttentionSync'",
            "Order = 3; Bf = 'BF-605'; Mode = 'BUTLER_WRITE'; Task = 'sleeperLiveWaiverProductionHydration'",
            "Order = 4; Bf = 'BF-606'; Mode = 'BUTLER_WRITE'; Task = 'sleeperLiveWaiverAvailabilitySync'",
            "Order = 5; Bf = 'BF-607'; Mode = 'BUTLER_WRITE'; Task = 'sleeperLiveWaiverCurrentWeekStatSync'",
            "Order = 6; Bf = 'BF-612'; Mode = 'BUTLER_WRITE'; Task = 'sleeperLiveWaiverTargetRosterProductionHydration'",
            "Order = 7; Bf = 'BF-618/BF-620'; Mode = 'READ_ONLY'; Task = 'sleeperLiveWaiverFinalRecommendationBundle'",
            "Order = 8; Bf = 'BF-627'; Mode = 'BUTLER_WRITE'; Task = 'sleeperLiveWaiverRecommendationAuditCapture'",
            "Order = 9; Bf = 'BF-629/BF-631/BF-633/BF-635'; Mode = 'READ_ONLY'; Task = 'sleeperLiveWaiverLatestGovernedDecisionSummary'"
        };
        for (String step : exactSteps) assertTrue(runner.contains(step));
    }

    @Test
    void onlyNoTransactionOrWarningRecommendedStatesCanReachTheFixedCycle() throws Exception {
        String runner = script("scripts/sleeper-live-waiver-no-transaction-refresh.ps1");

        assertTrue(runner.contains("$decisionState -ceq 'NO_TRANSACTION_TO_ACT_ON'"));
        assertTrue(runner.contains("$decisionState -ceq 'CURRENT_REFRESH_RECOMMENDED'"));
        assertTrue(runner.contains("is not authorized for browser refresh"));
        assertFalse(runner.contains("$decisionState -ceq 'CURRENT_AND_ACTIONABLE'"));
        assertFalse(runner.contains("$decisionState -ceq 'STALE_DO_NOT_ACT'"));
        assertFalse(runner.contains("$decisionState -ceq 'TRANSACTION_PENDING_DO_NOT_DUPLICATE'"));
        assertFalse(runner.contains("$decisionState -ceq 'TRANSACTION_ALREADY_COMPLETE'"));

        int stageBlock = runner.indexOf("$steps = @(");
        assertTrue(stageBlock >= 0);
        String stages = runner.substring(stageBlock);
        int previous = -1;
        for (String task : ORDERED_TASKS) {
            int current = stages.indexOf(task);
            assertTrue(current > previous, "BF-676 fixed stage order drifted at " + task);
            previous = current;
        }
    }

    @Test
    void confirmationExplainsWarningRefreshCanPreserveChangeOrRemoveRecommendation() throws Exception {
        String refresh = script("scripts/butler-decision-refresh.ps1");

        assertTrue(refresh.contains("BF-635 reports the approved six-hour refresh warning"));
        assertTrue(refresh.contains("BF-636 supplies the exact ready nine-step plan"));
        assertTrue(refresh.contains("may preserve it, change it, or produce no governed transaction"));
        assertTrue(refresh.contains("Fully current actionable, stale hard-gate, pending, completed/unconverged, and unknown states are blocked"));
        assertTrue(refresh.contains("This does not submit a waiver move to Sleeper."));
    }

    @Test
    void bf676KeepsSinglePostBoundaryAndNoSleeperTransactionExecution() throws Exception {
        String shell = script("scripts/butler-app-shell.ps1");
        String worker = script("scripts/butler-app-request-worker.ps1");
        String refresh = script("scripts/butler-decision-refresh.ps1");
        String runner = script("scripts/sleeper-live-waiver-no-transaction-refresh.ps1");

        assertTrue(worker.contains("if ($parts[0] -eq 'POST')"));
        assertTrue(worker.contains("if ($requestTarget -cne '/refresh')"));
        assertTrue(worker.contains("$SubmittedToken -cne [string]$State.Token"));
        assertTrue(worker.contains("$State.Token = New-DecisionRefreshToken"));
        assertTrue(shell.contains("[hashtable]::Synchronized(@{ Token = $decisionRefreshToken })"));
        assertFalse(refresh.contains("<script"));
        assertFalse(refresh.contains("javascript:"));
        assertFalse(runner.contains("create_transaction"));
        assertFalse(runner.contains("submitTransaction"));
        assertFalse(runner.contains("Invoke-Expression"));
        assertTrue(runner.contains("This runner never submits, cancels, or replaces a Sleeper transaction and never sets FAAB."));
    }

    @Test
    void bf676FilesRemainAsciiOnly() throws Exception {
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
        throw new IOException("BF-676 test could not locate " + relativePath);
    }
}
