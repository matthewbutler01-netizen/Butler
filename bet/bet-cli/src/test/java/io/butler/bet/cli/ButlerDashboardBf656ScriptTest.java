package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardBf656ScriptTest {

    @Test
    void eachRefreshStepExposesItsExactGovernedCommandInReadOnlyNativeField() throws Exception {
        String script = script();
        assertTrue(script.contains("$step.Command"));
        assertTrue(script.contains("<textarea class=\"refresh-command-copy\""));
        assertTrue(script.contains("readonly"));
        assertTrue(script.contains("$(ConvertTo-HtmlText $step.Command)"));
        assertTrue(script.contains("Press Ctrl+A, then Ctrl+C"));
    }

    @Test
    void copySafetyDoesNotRelaxNoJavascriptDashboardContract() throws Exception {
        String script = script();
        assertFalse(script.contains("<script"));
        assertFalse(script.contains("navigator.clipboard"));
        assertFalse(script.contains("data-refresh-command-b64"));
        assertFalse(script.contains("onclick="));
        assertFalse(script.contains("javascript:"));
        assertTrue(script.contains("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'"));
    }

    @Test
    void bf656AddsNoExecutionOrBatchControl() throws Exception {
        String script = script();
        assertFalse(script.contains("/refresh/run"));
        assertFalse(script.contains("Invoke-GovernedRefreshStep"));
        assertFalse(script.contains("Start-GovernedRefresh"));
        assertFalse(script.contains(">Run command<"));
        assertFalse(script.contains(">Run next<"));
        assertFalse(script.contains(">Run all<"));
        assertFalse(script.contains(">Copy all<"));
    }

    @Test
    void existingManualOnlyAndBf655SourceContractsRemainPresent() throws Exception {
        String script = script();
        assertTrue(script.contains("BF-636 executes none of these commands"));
        assertTrue(script.contains("Run these manually, one at a time"));
        assertTrue(script.contains("function Get-GovernedManualRefreshPlanView"));
        assertTrue(script.contains("BF-655 BLOCKED: BF-636 ready plan must contain exactly nine rendered steps"));
        assertTrue(script.contains("Why this move?"));
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
        throw new IOException("BF-656 test could not locate scripts/butler-dashboard.ps1");
    }
}
