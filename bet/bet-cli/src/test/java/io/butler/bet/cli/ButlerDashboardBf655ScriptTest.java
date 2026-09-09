package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardBf655ScriptTest {

    @Test
    void refreshPlanIsParsedOnlyFromExistingBf636SummaryProjection() throws Exception {
        String script = script();
        assertTrue(script.contains("function Get-GovernedManualRefreshPlanView"));
        assertTrue(script.contains("Decision status:"));
        assertTrue(script.contains("CURRENT_REFRESH_RECOMMENDED"));
        assertTrue(script.contains("BF-636 - governed MANUAL refresh plan"));
        assertTrue(script.contains("Plan state:"));
        assertTrue(script.contains("MANUAL_REFRESH_PLAN_READY"));
        assertTrue(script.contains("Operator instruction:"));
        assertTrue(script.contains("BF-655 BLOCKED: CURRENT_REFRESH_RECOMMENDED requires BF-636 MANUAL_REFRESH_PLAN_READY"));
    }

    @Test
    void parserRequiresExactlyNineOrderedSourceStepsAndCompleteFields() throws Exception {
        String script = script();
        assertTrue(script.contains("$stepMatches.Count -ne 9"));
        assertTrue(script.contains("BF-655 BLOCKED: BF-636 ready plan must contain exactly nine rendered steps"));
        assertTrue(script.contains("$order -ne ($index + 1)"));
        assertTrue(script.contains("BF-655 BLOCKED: BF-636 step order is malformed or non-contiguous"));
        assertTrue(script.contains("Order = $order"));
        assertTrue(script.contains("Bf = $match.Groups['bf'].Value.Trim()"));
        assertTrue(script.contains("Mode = $match.Groups['mode'].Value.Trim()"));
        assertTrue(script.contains("TaskName = $match.Groups['task'].Value.Trim()"));
        assertTrue(script.contains("Command = $match.Groups['command'].Value.Trim()"));
        assertTrue(script.contains("Purpose = $match.Groups['purpose'].Value.Trim()"));
    }

    @Test
    void dashboardRendersSourceModeCommandAndPurposeWithoutExecutionControls() throws Exception {
        String script = script();
        assertTrue(script.contains("Governed manual refresh plan"));
        assertTrue(script.contains("Run these manually, one at a time"));
        assertTrue(script.contains("$step.Mode"));
        assertTrue(script.contains("$step.Command"));
        assertTrue(script.contains("$step.Purpose"));
        assertTrue(script.contains("BF-636 executes none of these commands"));
        assertFalse(script.contains("/refresh/run"));
        assertFalse(script.contains("Invoke-GovernedRefreshStep"));
        assertFalse(script.contains("Start-GovernedRefresh"));
    }

    @Test
    void dashboardDoesNotHardCodeBf636TaskSequenceAsFallback() throws Exception {
        String script = script();
        assertFalse(script.contains("BF655_REFRESH_TASKS"));
        assertFalse(script.contains("Get-HardCodedRefreshPlan"));
        assertFalse(script.contains("sleeperLiveWaiverSnapshotSync --args"));
        assertFalse(script.contains("sleeperLiveWaiverMarketAttentionSync --args"));
        assertFalse(script.contains("sleeperLiveWaiverRecommendationAuditCapture --args"));
    }

    @Test
    void otherDecisionStatesDoNotExposeManualRefreshPlan() throws Exception {
        String script = script();
        assertTrue(script.contains("if ($decisionState -cne \"CURRENT_REFRESH_RECOMMENDED\")"));
        assertTrue(script.contains("Active = $false"));
        assertTrue(script.contains("$refreshPlanSection = \"\""));
    }

    @Test
    void bf654ExplanationRemainsPresent() throws Exception {
        String script = script();
        assertTrue(script.contains("function Get-GovernedExplanationView"));
        assertTrue(script.contains("Why this move?"));
        assertTrue(script.contains("Invoke-ButlerReadOnlyExplanationLookup"));
    }

    private static String script() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve("scripts/butler-dashboard.ps1");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-655 test could not locate scripts/butler-dashboard.ps1");
    }
}
