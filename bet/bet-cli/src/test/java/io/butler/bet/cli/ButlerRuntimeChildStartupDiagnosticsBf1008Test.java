package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerRuntimeChildStartupDiagnosticsBf1008Test {

    @Test
    void preservedCoreStartupSurfacesChildStderr() throws Exception {
        String core = source("scripts/butler-app-shell-core.ps1");

        assertTrue(core.contains("$start.RedirectStandardError = $true"));
        assertTrue(core.contains("$Process.StandardError.ReadToEnd().Trim()"));
        assertTrue(core.contains("preserved inner core on port $BackendPort exited during startup."));
        assertTrue(core.contains("No child stderr was captured."));
    }

    @Test
    void bf742SharedWorkerStagingPreservesDashboardStderrCapture() throws Exception {
        String bf742 = source("scripts/butler-core-bf742-transform.ps1");

        assertTrue(bf742.contains("$coreLaunchOriginal = @'"));
        assertTrue(bf742.contains("$start.RedirectStandardError = $true"));
        assertTrue(bf742.contains("$start.EnvironmentVariables[\"BUTLER_APP_INTERNAL_DASHBOARD_TOKEN\"]"));
    }

    @Test
    void governedDashboardStartupSurfacesChildStderr() throws Exception {
        String coreSingle = source("scripts/butler-app-shell-core-single.ps1");

        assertTrue(coreSingle.contains("$start.RedirectStandardError = $true"));
        assertTrue(coreSingle.contains("$Process.StandardError.ReadToEnd().Trim()"));
        assertTrue(coreSingle.contains("governed Butler dashboard exited during app-shell startup."));
        assertTrue(coreSingle.contains("No child stderr was captured."));
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
        throw new IOException("BF-1008 test could not locate " + relativePath);
    }
}
