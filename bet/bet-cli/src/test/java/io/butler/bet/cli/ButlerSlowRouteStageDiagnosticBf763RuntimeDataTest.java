package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSlowRouteStageDiagnosticBf763RuntimeDataTest {

    @Test
    void slowRouteDiagnosticUsesGovernedRuntimeDatabaseInsteadOfSourceTreeDatabase() throws Exception {
        String script = source("scripts/butler-slow-route-stage-diagnostic.ps1");

        assertTrue(script.contains("BUTLER_APP_DATA_DIR"));
        assertTrue(script.contains("Join-Path $configDir 'data'"));
        assertTrue(script.contains("[IO.Path]::IsPathRooted($configuredDataDir)"));
        assertTrue(script.contains("data directory must be outside the source/package tree"));
        assertTrue(script.contains("$databasePath = Join-Path $dataDir 'butler.db'"));
        assertTrue(script.contains("governed Butler runtime database is missing"));
        assertTrue(script.contains("Push-Location $dataDir"));
        assertFalse(script.contains("Push-Location $betCliDir"));
    }

    @Test
    void slowRouteDiagnosticKeepsPreparedRuntimeAndReadOnlyBoundary() throws Exception {
        String script = source("scripts/butler-slow-route-stage-diagnostic.ps1");

        assertTrue(script.contains("build\\install\\bet-cli\\lib"));
        assertTrue(script.contains("io.butler.bet.cli.ButlerWarmedSlowRouteStageDiagnosticCli"));
        assertTrue(script.contains("--enable-native-access=ALL-UNNAMED"));
        assertTrue(script.contains("BF-763 data: "));
        assertTrue(script.contains("/refresh excluded; no Butler or Sleeper write path is invoked"));
        assertFalse(script.contains("submitTransaction"));
        assertFalse(script.contains("create_transaction"));

        byte[] ascii = script.getBytes(StandardCharsets.US_ASCII);
        assertEquals(script, new String(ascii, StandardCharsets.US_ASCII));
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
        throw new IOException("BF-763 test could not locate " + relativePath);
    }
}
