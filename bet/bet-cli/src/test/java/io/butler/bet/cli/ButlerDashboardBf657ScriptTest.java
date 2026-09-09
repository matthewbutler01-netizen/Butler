package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardBf657ScriptTest {

    @Test
    void nextDecisionPlanIsParsedOnlyFromExistingBf640SummaryProjection() throws Exception {
        String script = script();
        assertTrue(script.contains("function Get-GovernedNextDecisionPlanView"));
        assertTrue(script.contains("Decision status:"));
        assertTrue(script.contains("TRANSACTION_ALREADY_COMPLETE"));
        assertTrue(script.contains("BF-639 post-transaction roster convergence:"));
        assertTrue(script.contains("POST_TRANSACTION_ROSTER_CONVERGED"));
        assertTrue(script.contains("BF-640 - governed MANUAL next-decision plan"));
        assertTrue(script.contains("NEXT_DECISION_PLAN_READY"));
        assertTrue(script.contains("BF-657 BLOCKED: completed/converged lifecycle requires exactly one BF-640 governed manual next-decision plan"));
    }

    @Test
    void nextDecisionParserRequiresExactlyNineOrderedSourceSteps() throws Exception {
        String script = script();
        assertTrue(script.contains("BF-657 BLOCKED: BF-640 ready plan must contain exactly nine rendered steps"));
        assertTrue(script.contains("BF-657 BLOCKED: BF-640 step order is malformed or non-contiguous"));
        assertTrue(script.contains("BF-657 BLOCKED: BF-640 step mode is unsupported:"));
        assertTrue(script.contains("Bf = $match.Groups['bf'].Value.Trim()"));
        assertTrue(script.contains("Command = $match.Groups['command'].Value.Trim()"));
        assertTrue(script.contains("Purpose = $match.Groups['purpose'].Value.Trim()"));
    }

    @Test
    void dashboardRendersNoScriptCopySafeNextDecisionPlan() throws Exception {
        String script = script();
        assertTrue(script.contains("$nextDecisionPlan = Get-GovernedNextDecisionPlanView -Summary $Summary"));
        assertTrue(script.contains("Governed manual next-decision plan"));
        assertTrue(script.contains("Start Butler's next decision safely"));
        assertTrue(script.contains("BF-640 executes none of these commands"));
        assertTrue(script.contains("class=\"refresh-command-copy\""));
        assertTrue(script.contains("Ctrl+A"));
        assertTrue(script.contains("Ctrl+C"));
        assertFalse(script.contains("navigator.clipboard"));
        assertFalse(script.contains("<script"));
    }

    @Test
    void staleRefreshAndCompletedNextDecisionPlansRemainSeparate() throws Exception {
        String script = script();
        assertTrue(script.contains("if ($decisionState -cne \"CURRENT_REFRESH_RECOMMENDED\")"));
        assertTrue(script.contains("if ($decisionState -cne \"TRANSACTION_ALREADY_COMPLETE\")"));
        assertTrue(script.contains("$refreshPlanSection = \"\""));
        assertTrue(script.contains("$nextDecisionPlanSection = \"\""));
        assertTrue(script.contains("$refreshPlanSection"));
        assertTrue(script.contains("$nextDecisionPlanSection"));
        assertFalse(script.contains("BF657_NEXT_DECISION_TASKS"));
        assertFalse(script.contains("Get-HardCodedNextDecisionPlan"));
    }

    @Test
    void bf657AddsNoExecutionOrBatchControls() throws Exception {
        String script = script();
        assertFalse(script.contains("/next-decision/run"));
        assertFalse(script.contains("Invoke-GovernedNextDecisionStep"));
        assertFalse(script.contains("Start-GovernedNextDecision"));
        assertFalse(script.contains(">Run command<"));
        assertFalse(script.contains(">Run next<"));
        assertFalse(script.contains(">Run all<"));
        assertFalse(script.contains(">Copy all<"));
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
        throw new IOException("BF-657 test could not locate scripts/butler-dashboard.ps1");
    }
}
