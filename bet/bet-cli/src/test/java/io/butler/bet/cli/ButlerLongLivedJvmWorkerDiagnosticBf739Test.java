package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLongLivedJvmWorkerDiagnosticBf739Test {
    @Test
    void diagnosticUsesSixPersistentHelpOnlyWorkersAndCleansThemUp() throws Exception {
        String script = source("scripts/butler-long-lived-jvm-worker-diagnostic.ps1");

        assertTrue(script.contains("io.butler.bet.cli.ButlerReadOnlyJvmWorker"));
        assertTrue(script.contains("$index -lt 6"));
        assertTrue(script.contains("HELP`t$requestId"));
        assertTrue(script.contains("warm_worker_seq_p50_ms="));
        assertTrue(script.contains("warm_worker_c6_wall_p50_ms="));
        assertTrue(script.contains("Stop-Bf739Worker"));
        assertTrue(script.contains("production app-shell routing unchanged"));
        assertFalse(script.contains("production-refresh"));
        assertFalse(script.contains("butler.db"));
        assertFalse(script.contains("Invoke-RestMethod"));
        assertFalse(script.contains("Invoke-WebRequest"));
        assertFalse(script.contains("--args="));
    }

    @Test
    void acceptanceRunsWorkerProofAfterBf738StartupDiagnostic() throws Exception {
        String command = source("scripts/butler-acceptance.cmd");
        int startup = command.indexOf("butler-dispatch-startup-diagnostic.ps1");
        int worker = command.indexOf("butler-long-lived-jvm-worker-diagnostic.ps1");

        assertTrue(startup >= 0);
        assertTrue(worker > startup);
        assertTrue(command.substring(startup, worker).contains("if errorlevel 1 exit /b %ERRORLEVEL%"));
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
        throw new IOException("BF-739 test could not locate " + relativePath);
    }
}