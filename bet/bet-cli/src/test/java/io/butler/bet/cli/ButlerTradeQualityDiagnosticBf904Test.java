package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeQualityDiagnosticBf904Test {

    @Test
    void diagnosticSamplesLiveLowMidHighOneForOneDealsAndReportsDecisionQuality() throws Exception {
        String script = source("scripts/butler-trade-quality-diagnostic.ps1");

        for (String marker : new String[]{
                "Butler Trade Analyzer quality diagnostic (BF-904)",
                "low/mid/high valued asset from each side",
                "maximum 9 exact 1-for-1 deals",
                "Select-SampleAssets",
                "Action distribution:",
                "Evidence complete:",
                "First rejected-deal counter state:",
                "BF-904 DIAGNOSTIC: COMPLETE",
                "Working tree: CLEAN"
        }) {
            assertTrue(script.contains(marker), "BF-904 diagnostic missing " + marker);
        }

        assertTrue(script.contains("/trade?load=1"));
        assertTrue(script.contains("&evaluate=1&give="));
        assertTrue(script.contains("&receive="));
        assertTrue(script.contains("&counter=1"));
    }

    @Test
    void diagnosticRemainsGetOnlyLoopbackAndOwnsItsProcess() throws Exception {
        String script = source("scripts/butler-trade-quality-diagnostic.ps1");
        String wrapper = source("scripts/butler-trade-quality-diagnostic.cmd");

        assertTrue(script.contains("$request.Method = 'GET'"));
        assertTrue(script.contains("127.0.0.1"));
        assertTrue(script.contains("/refresh excluded"));
        assertTrue(script.contains("taskkill /PID $Process.Id /T /F"));
        assertTrue(script.contains("git status --porcelain=v1 --untracked-files=all"));
        assertTrue(wrapper.contains("-ExecutionPolicy Bypass"));
        assertTrue(wrapper.contains("butler-trade-quality-diagnostic.ps1"));

        assertFalse(script.contains("Method = 'POST'"));
        assertFalse(script.contains("Method = \"POST\""));
        assertFalse(script.contains("Invoke-RestMethod"));
        assertFalse(script.contains("Invoke-WebRequest"));
        assertFalse(script.contains("submitTransaction"));
        assertFalse(script.contains("create_transaction"));
        assertFalse(script.contains("/refresh?"));
        assertFalse(script.matches("(?s).*\\R\\s+-(?:and|or)\\b.*"),
            "Windows PowerShell 5.1 diagnostic must not start continuation lines with boolean operators");
        assertFalse(script.matches("(?s).*\\R\\s*\\+\\s+.*"),
            "Windows PowerShell 5.1 diagnostic must not start continuation lines with plus");
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IOException("BF-904 test could not locate " + relativePath);
    }
}
