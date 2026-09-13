package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf708StartupDiagnosticsTest {

    @Test
    void acceptanceContinuouslyCapturesOwnedStartupOutput() throws Exception {
        String runner = source("scripts/butler-acceptance.ps1");

        assertTrue(runner.contains("$start.RedirectStandardOutput = $true"));
        assertTrue(runner.contains("$start.RedirectStandardError = $true"));
        assertTrue(runner.contains("$process.StandardOutput.ReadToEndAsync()"));
        assertTrue(runner.contains("$process.StandardError.ReadToEndAsync()"));
        assertTrue(runner.contains("ButlerStdoutTask"));
        assertTrue(runner.contains("ButlerStderrTask"));
    }

    @Test
    void earlyStartupExitIncludesBoundedNormalizedOutput() throws Exception {
        String runner = source("scripts/butler-acceptance.ps1");

        assertTrue(runner.contains("function Get-BoundedStartupOutput"));
        assertTrue(runner.contains("[regex]::Replace($text, '\\s+', ' ').Trim()"));
        assertTrue(runner.contains("if ($text.Length -gt 900)"));
        assertTrue(runner.contains("if ($combined.Length -gt 1800)"));
        assertTrue(runner.contains("startup=$diagnostic"));
        assertTrue(runner.contains("Butler exited during startup with code"));
    }

    @Test
    void diagnosticsDoNotBroadenAcceptanceBoundary() throws Exception {
        String runner = source("scripts/butler-acceptance.ps1");

        assertTrue(runner.contains("$process = Start-OwnedButler -SelectedPort $selectedPort"));
        assertTrue(runner.contains("& $taskkill /PID $Process.Id /T /F"));
        assertFalse(runner.contains("/refresh"));
        assertFalse(runner.contains("Stop-Process"));
    }

    @Test
    void bf708SourcesRemainAsciiOnly() throws Exception {
        String runner = source("scripts/butler-acceptance.ps1");
        byte[] encoded = runner.getBytes(StandardCharsets.US_ASCII);
        assertEquals(runner, new String(encoded, StandardCharsets.US_ASCII));
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
        throw new IOException("BF-708 test could not locate " + relativePath);
    }
}
