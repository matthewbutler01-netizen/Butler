package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerJavaHostPreflightBf782Test {

    @Test
    void preflightRequiresJava25OrNewerAndFailsClosed() throws Exception {
        String preflight = source("scripts/butler-java-preflight.ps1");

        assertTrue(preflight.contains("[int]$MinimumMajor = 25"));
        assertTrue(preflight.contains("& $java '-version'"));
        assertTrue(preflight.contains("$javaExit -ne 0"));
        assertTrue(preflight.contains("version\\s+\"(?<version>[0-9]+(?:\\.[0-9]+)*)\""));
        assertTrue(preflight.contains("$major -eq 1"));
        assertTrue(preflight.contains("$major -lt $MinimumMajor"));
        assertTrue(preflight.contains("Java $MinimumMajor or newer is required"));
        assertTrue(preflight.contains("BF-782 BLOCKED"));
    }

    @Test
    void preflightPrefersJavaHomeBeforePathJava() throws Exception {
        String preflight = source("scripts/butler-java-preflight.ps1");

        int javaHome = preflight.indexOf("$env:JAVA_HOME");
        int javaHomeExecutable = preflight.indexOf("'bin\\java.exe'");
        int pathLookup = preflight.indexOf("Get-Command java.exe");

        assertTrue(javaHome >= 0);
        assertTrue(javaHomeExecutable > javaHome);
        assertTrue(pathLookup > javaHomeExecutable,
            "BF-782 must prefer a valid JAVA_HOME java.exe before PATH resolution");
    }

    @Test
    void supportedAppGuardSkipsJavaForResetAndChecksBeforeRuntimeWrites() throws Exception {
        String guard = source("scripts/butler-app-guard.ps1");

        int reset = guard.indexOf("if ($ResetLeague)");
        int resetExit = guard.indexOf("exit 0", reset);
        int preflight = guard.indexOf("& $javaPreflight");
        int localAppData = guard.indexOf("$localAppData = $env:LOCALAPPDATA");
        int lockDirectoryCreate = guard.indexOf("[IO.Directory]::CreateDirectory($lockDirectory)");

        assertTrue(guard.contains("$javaPreflight = Join-Path $scriptDir \"butler-java-preflight.ps1\""));
        assertTrue(guard.contains("BF-782 BLOCKED: Butler Java preflight not found"));
        assertTrue(reset >= 0);
        assertTrue(resetExit > reset);
        assertTrue(preflight > resetExit,
            "-ResetLeague must remain a configuration-only action that does not require Java");
        assertTrue(localAppData > preflight);
        assertTrue(lockDirectoryCreate > preflight,
            "normal app startup must validate Java before app lock/runtime directory writes");
    }

    @Test
    void preflightDoesNotTouchRuntimeDataOrNetworkAndWindowsSourcesAreAscii() throws Exception {
        String preflight = source("scripts/butler-java-preflight.ps1");
        String guard = source("scripts/butler-app-guard.ps1");

        assertFalse(preflight.contains("butler.db"));
        assertFalse(preflight.contains("BUTLER_APP_DATA_DIR"));
        assertFalse(preflight.contains("Invoke-WebRequest"));
        assertFalse(preflight.contains("Invoke-RestMethod"));
        assertFalse(preflight.contains("Start-Process"));

        assertAscii(preflight);
        assertAscii(guard);
    }

    @Test
    void readmeStatesJava25OrNewerHostRequirement() throws Exception {
        String readme = source("README.md");
        assertTrue(readme.contains("Java 25 or newer available to the host."));
    }

    private static void assertAscii(String value) {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        assertEquals(value, new String(encoded, StandardCharsets.US_ASCII));
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
        throw new IOException("BF-782 test could not locate " + relativePath);
    }
}
