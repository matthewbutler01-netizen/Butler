package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardBf658ScriptTest {

    @Test
    void captureCommandRequiresExactMissingExplanationAuditState() throws Exception {
        String script = script();
        assertTrue(script.contains("function Get-GovernedExplanationCaptureView"));
        assertTrue(script.contains("EXPLANATION_NOT_CAPTURED"));
        assertTrue(script.contains("BF-658 BLOCKED: current Butler league id is malformed for governed explanation capture"));
        assertTrue(script.contains("BF-658 BLOCKED: current BF-627 audit id is malformed for governed explanation capture"));
        assertTrue(script.contains("BF-658 BLOCKED: reconciled BF-603/BF-602 lineage is unavailable for governed explanation capture"));
        assertTrue(script.contains("BF-658 BLOCKED: reconciled ADD/DROP exact Sleeper ids are unavailable for governed explanation capture"));
    }

    @Test
    void dashboardRendersExactManualBf653CaptureCommandInCopySafeField() throws Exception {
        String script = script();
        assertTrue(script.contains("sleeperLiveWaiverGovernedExplanationCapture"));
        assertTrue(script.contains("Capture the governed explanation"));
        assertTrue(script.contains("BF-653 is the only writer"));
        assertTrue(script.contains("class=\"refresh-command-copy\""));
        assertTrue(script.contains("Copy safely: focus the read-only field, then Press Ctrl+A, then Ctrl+C."));
        assertTrue(script.contains("$LeagueId + ' ' + $Explanation.AuditId"));
    }

    @Test
    void persistedExplanationSuppressesCapturePromptAndRemainsAuthoritative() throws Exception {
        String script = script();
        assertTrue(script.contains("if ($Explanation.Ready)"));
        assertTrue(script.contains("Active = $false"));
        assertTrue(script.contains("Persisted BF-653 explanation for this immutable BF-627 audit"));
        assertTrue(script.contains("$explanationCapture = Get-GovernedExplanationCaptureView -Explanation $explanation"));
        assertTrue(script.contains("if ($explanationCapture.Active)"));
    }

    @Test
    void bf658AddsNoBrowserExecutionSurface() throws Exception {
        String script = script();
        assertFalse(script.contains("/explanation/capture"));
        assertFalse(script.contains("Invoke-ButlerExplanationCapture"));
        assertFalse(script.contains("Start-ExplanationCapture"));
        assertFalse(script.contains("navigator.clipboard"));
        assertFalse(script.contains("<script"));
        assertFalse(script.contains("onclick="));
        assertTrue(script.contains("dashboard does not execute BF-653 capture"));
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
        throw new IOException("BF-658 test could not locate scripts/butler-dashboard.ps1");
    }
}
