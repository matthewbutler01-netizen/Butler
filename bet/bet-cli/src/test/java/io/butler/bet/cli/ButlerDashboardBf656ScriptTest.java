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
    void eachRefreshStepCopiesOnlyItsExactGovernedCommand() throws Exception {
        String script = script();
        assertTrue(script.contains("$step.Command"));
        assertTrue(script.contains("[Convert]::ToBase64String"));
        assertTrue(script.contains("data-refresh-command-b64"));
        assertTrue(script.contains("Copy command"));
        assertTrue(script.contains("navigator.clipboard.writeText(command)"));
        assertTrue(script.contains("atob(encoded)"));
    }

    @Test
    void copyControlReportsLocalClipboardOutcomeOnly() throws Exception {
        String script = script();
        assertTrue(script.contains("Copied"));
        assertTrue(script.contains("Copy failed"));
        assertTrue(script.contains("copy-refresh-status"));
        assertTrue(script.contains("type=\"button\""));
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
