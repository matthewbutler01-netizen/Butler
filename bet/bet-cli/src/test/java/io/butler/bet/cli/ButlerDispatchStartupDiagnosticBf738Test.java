package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDispatchStartupDiagnosticBf738Test {
    @Test
    void diagnosticUsesHelpOnlyAndMeasuresRawAndProxyConcurrencyWithoutProviderWork() throws Exception {
        String script = source("scripts/butler-dispatch-startup-diagnostic.ps1");

        assertTrue(script.contains("ButlerCommandRouter help"));
        assertTrue(script.contains("--args=help"));
        assertTrue(script.contains("Invoke-ProbeBatch -Mode RAW_JAVA -Count 6"));
        assertTrue(script.contains("Invoke-ProbeBatch -Mode PROXY_CHAIN -Count 6"));
        assertTrue(script.contains("Dispatch startup timing (diagnostic):"));
        assertTrue(script.contains("dispatch-diagnostic-{0}"));
        assertTrue(script.contains("Restore-DiagnosticEnvironment"));
        assertFalse(script.contains("sleeperLiveWaiver"));
        assertFalse(script.contains("production-refresh"));
        assertFalse(script.contains("butler.db"));
        assertFalse(script.contains("Invoke-RestMethod"));
        assertFalse(script.contains("Invoke-WebRequest"));
    }

    @Test
    void acceptanceRunsDispatchDiagnosticAfterExistingReadOnlyStageDiagnostics() throws Exception {
        String command = source("scripts/butler-acceptance.cmd");
        int slow = command.indexOf("butler-slow-route-stage-diagnostic.ps1");
        int dispatch = command.indexOf("butler-dispatch-startup-diagnostic.ps1");

        assertTrue(slow >= 0);
        assertTrue(dispatch > slow);
        assertTrue(command.substring(slow, dispatch).contains("if errorlevel 1 exit /b %ERRORLEVEL%"));
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
        throw new IOException("BF-738 test could not locate " + relativePath);
    }
}
